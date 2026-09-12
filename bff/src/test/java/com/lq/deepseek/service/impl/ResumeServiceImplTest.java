package com.lq.deepseek.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lq.deepseek.common.BusinessException;
import com.lq.deepseek.common.ErrorCode;
import com.lq.deepseek.config.props.LqProperties;
import com.lq.deepseek.domain.entity.FileAsset;
import com.lq.deepseek.domain.entity.ResumeVersion;
import com.lq.deepseek.domain.mapper.FileAssetMapper;
import com.lq.deepseek.domain.mapper.ResumeVersionMapper;
import com.lq.deepseek.dto.ResumeDtos;
import com.lq.deepseek.gateway.AiInvocationGateway;
import com.lq.deepseek.gateway.model.AgentInvokeCommand;
import com.lq.deepseek.gateway.model.AgentInvokeResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 简历中心行为测试。
 *
 * <p>钉死三条产品级约束：重复上传不重复扣费、解析失败必须落在资产状态上（可重试）、
 * 额度不足必须原样上抛而不能伪装成"解析失败"。
 */
class ResumeServiceImplTest {

    private static final String RESUME_TEXT = "张三\nJava 5 年\n项目经历\n- 订单中心重构，耗时下降 60%";

    @TempDir
    Path tempDir;

    private FileAssetMapper fileAssetMapper;
    private ResumeVersionMapper resumeVersionMapper;
    private AiInvocationGateway gateway;
    private ResumeServiceImpl service;

    @BeforeEach
    void setUp() {
        fileAssetMapper = mock(FileAssetMapper.class);
        resumeVersionMapper = mock(ResumeVersionMapper.class);
        gateway = mock(AiInvocationGateway.class);
        LqProperties properties = new LqProperties();
        properties.getStorage().setLocalRoot(tempDir.toString());
        when(resumeVersionMapper.insert(any(ResumeVersion.class))).thenReturn(1);
        service = new ResumeServiceImpl(fileAssetMapper, resumeVersionMapper, gateway, properties, new ObjectMapper());
    }

    @Test
    void upload_newResume_parsesAndPersistsProfile() {
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), anyString())).thenReturn(null);
        when(fileAssetMapper.insert(any(FileAsset.class))).thenAnswer(invocation -> {
            FileAsset asset = invocation.getArgument(0);
            asset.setId(1001L);
            return 1;
        });
        when(gateway.invoke(any())).thenReturn(successResult());

        ResumeDtos.ResumeVO vo = service.uploadResume(7L, resumeFile("resume.txt", RESUME_TEXT));

        assertThat(vo.getAssetId()).isEqualTo(1001L);
        assertThat(vo.getParseStatus()).isEqualTo("SUCCESS");
        assertThat(vo.getDeduplicated()).isFalse();
        assertThat(vo.getProfile()).containsEntry("sourceKind", "FILE");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("RESUME_PARSE");
        assertThat(command.getValue().getExpectedCredit()).isEqualTo(10L);
        assertThat(command.getValue().getPayload()).containsKeys("fileName", "fileBase64");

        verify(fileAssetMapper).updateParseState(eq(1001L), eq("PARSING"), isNull(), isNull());
        verify(fileAssetMapper).updateParseState(eq(1001L), eq("SUCCESS"), anyString(), isNull());
    }

    @Test
    void upload_writesFileUnderStorageRoot() {
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), anyString())).thenReturn(null);
        ArgumentCaptor<FileAsset> inserted = ArgumentCaptor.forClass(FileAsset.class);
        when(fileAssetMapper.insert(inserted.capture())).thenAnswer(invocation -> {
            inserted.getValue().setId(1002L);
            return 1;
        });
        when(gateway.invoke(any())).thenReturn(successResult());

        service.uploadResume(7L, resumeFile("resume.txt", RESUME_TEXT));

        Path stored = tempDir.resolve(inserted.getValue().getObjectKey());
        assertThat(Files.exists(stored)).isTrue();
        // 内容指纹入库存的是 SHA-256，长度必须为 64，否则唯一索引形同虚设
        assertThat(inserted.getValue().getSha256()).hasSize(64);
    }

    @Test
    void upload_sameContentAgain_reusesAssetWithoutAgentCall() {
        FileAsset existing = new FileAsset();
        existing.setId(2001L);
        existing.setUserId(7L);
        existing.setFileName("resume.txt");
        existing.setSha256("a".repeat(64));
        existing.setParseStatus("SUCCESS");
        existing.setParseResult(Map.of("completeness", 88));
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), anyString())).thenReturn(existing);

        ResumeDtos.ResumeVO vo = service.uploadResume(7L, resumeFile("resume.txt", RESUME_TEXT));

        assertThat(vo.getDeduplicated()).isTrue();
        assertThat(vo.getProfile()).containsEntry("completeness", 88);
        verify(gateway, never()).invoke(any());
        verify(fileAssetMapper, never()).insert(any(FileAsset.class));
    }

    @Test
    void upload_unsupportedExtension_isRejectedBeforeAnyStorage() {
        assertThatThrownBy(() -> service.uploadResume(7L, resumeFile("resume.exe", "binary")))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_TYPE_UNSUPPORTED);

        verify(gateway, never()).invoke(any());
        verify(fileAssetMapper, never()).insert(any(FileAsset.class));
    }

    @Test
    void upload_emptyFile_isRejected() {
        MockMultipartFile empty = new MockMultipartFile("file", "resume.txt", "text/plain", new byte[0]);

        assertThatThrownBy(() -> service.uploadResume(7L, empty))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_EMPTY);
    }

    @Test
    void upload_parseFailure_marksAssetFailedWithReason() {
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), anyString())).thenReturn(null);
        when(fileAssetMapper.insert(any(FileAsset.class))).thenAnswer(invocation -> {
            FileAsset asset = invocation.getArgument(0);
            asset.setId(3001L);
            return 1;
        });
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-1").status("FAILED").errorCode("UNSUPPORTED_FILE_TYPE")
                .errorMsg("暂不支持的文件类型：.doc").build());

        ResumeDtos.ResumeVO vo = service.uploadResume(7L, resumeFile("resume.txt", RESUME_TEXT));

        assertThat(vo.getParseStatus()).isEqualTo("FAILED");
        assertThat(vo.getErrorMsg()).contains("暂不支持");
        verify(fileAssetMapper).updateParseState(eq(3001L), eq("FAILED"), isNull(), anyString());
    }

    @Test
    void upload_insufficientCredit_propagatesInsteadOfMaskingAsParseFailure() {
        when(fileAssetMapper.selectActiveBySha(anyLong(), anyString(), anyString())).thenReturn(null);
        when(fileAssetMapper.insert(any(FileAsset.class))).thenAnswer(invocation -> {
            FileAsset asset = invocation.getArgument(0);
            asset.setId(4001L);
            return 1;
        });
        when(gateway.invoke(any())).thenThrow(new BusinessException(ErrorCode.INSUFFICIENT_CREDIT));

        assertThatThrownBy(() -> service.uploadResume(7L, resumeFile("resume.txt", RESUME_TEXT)))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.INSUFFICIENT_CREDIT);

        // 状态仍要落 FAILED，保证重试入口可见（DDL 约束要求 FAILED 必须带 error_msg）
        verify(fileAssetMapper).updateParseState(eq(4001L), eq("FAILED"), isNull(), anyString());
    }

    @Test
    void getResume_ofOtherUser_isReportedAsNotFound() {
        FileAsset asset = new FileAsset();
        asset.setId(5001L);
        asset.setUserId(99L);
        when(fileAssetMapper.selectById(5001L)).thenReturn(asset);

        assertThatThrownBy(() -> service.getResume(7L, 5001L))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.FILE_NOT_FOUND);
    }

    @Test
    void listResumes_flattensProfileFields() {
        FileAsset asset = new FileAsset();
        asset.setId(6001L);
        asset.setUserId(7L);
        asset.setFileName("resume.pdf");
        asset.setParseStatus("SUCCESS");
        asset.setParseResult(Map.of(
                "completeness", 75,
                "experienceYears", 5,
                "skills", List.of("Java", "Redis", "Kafka")));
        when(fileAssetMapper.selectList(any())).thenReturn(List.of(asset));

        List<ResumeDtos.AssetBrief> briefs = service.listResumes(7L);

        assertThat(briefs).hasSize(1);
        assertThat(briefs.get(0).getCompleteness()).isEqualTo(75);
        assertThat(briefs.get(0).getSkillCount()).isEqualTo(3);
        assertThat(briefs.get(0).getExperienceYears()).isEqualTo(5);
    }

    @Test
    void polish_returnsPolishedTextWithReasons() {
        when(gateway.invoke(any())).thenReturn(AgentInvokeResult.builder()
                .runId("run-polish").status("SUCCEEDED").flightMode("OWNER").costCredit(2L)
                .output(Map.of(
                        "mode", "POLISH",
                        "target", "后端工程师",
                        "original", "负责订单系统",
                        "polished", "主导订单系统重构，接口耗时下降 60%",
                        "reasons", List.of("补充量化结果"),
                        "matchedSkills", List.of("Java")))
                .build());

        ResumeDtos.PolishRequest request = new ResumeDtos.PolishRequest();
        request.setSelectedText("负责订单系统");
        request.setJobTitle("后端工程师");

        ResumeDtos.PolishVO vo = service.polish(7L, request);

        assertThat(vo.getPolished()).contains("下降 60%");
        assertThat(vo.getReasons()).containsExactly("补充量化结果");
        assertThat(vo.getFlightMode()).isEqualTo("OWNER");
        assertThat(vo.getTarget()).isEqualTo("后端工程师");

        ArgumentCaptor<AgentInvokeCommand> command = ArgumentCaptor.forClass(AgentInvokeCommand.class);
        verify(gateway).invoke(command.capture());
        assertThat(command.getValue().getBizType()).isEqualTo("RESUME_QUESTION");
        assertThat(command.getValue().getPayload()).containsEntry("mode", "POLISH");
    }

    @Test
    void polish_withoutSelection_isRejectedLocally() {
        ResumeDtos.PolishRequest request = new ResumeDtos.PolishRequest();

        assertThatThrownBy(() -> service.polish(7L, request))
                .isInstanceOf(BusinessException.class)
                .extracting(e -> ((BusinessException) e).getErrorCode())
                .isEqualTo(ErrorCode.PARAM_INVALID);

        verify(gateway, never()).invoke(any());
    }

    // -----------------------------------------------------------------------

    private static AgentInvokeResult successResult() {
        return AgentInvokeResult.builder()
                .runId("run-parse").status("SUCCEEDED").flightMode("OWNER").costCredit(3L)
                .output(Map.of("sourceKind", "FILE", "completeness", 88, "skills", List.of("Java")))
                .build();
    }

    private static MockMultipartFile resumeFile(String name, String content) {
        return new MockMultipartFile("file", name, "text/plain", content.getBytes(StandardCharsets.UTF_8));
    }
}
