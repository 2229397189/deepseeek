/** html2pdf.js 模块声明（无官方类型）。docs §6.2 导出 PDF。 */
declare module 'html2pdf.js' {
  interface Html2PdfOptions {
    margin?: number | number[];
    filename?: string;
    image?: { type?: string; quality?: number };
    html2canvas?: Record<string, unknown>;
    jsPDF?: Record<string, unknown>;
    pagebreak?: Record<string, unknown>;
  }
  interface Html2Pdf {
    set(opt: Html2PdfOptions): Html2Pdf;
    from(element: HTMLElement | string): Html2Pdf;
    save(): Promise<void>;
    toPdf(): Html2Pdf;
    get(key: string): Html2Pdf;
    output(type: string, options?: Record<string, unknown>): Promise<unknown>;
  }
  function html2pdf(): Html2Pdf;
  export default html2pdf;
}
