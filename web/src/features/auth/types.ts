/** 鉴权相关 DTO。docs §5.1 /auth/*。 */
export interface RegisterRequest {
  username: string;
  password: string;
  email?: string;
  nickname?: string;
  inviteCode?: string;
}

export interface LoginRequest {
  username: string;
  password: string;
}

export interface LoginResponse {
  token: string;
  tokenName: string;
  expiresIn: number;
  user: UserVO;
}

export interface UserVO {
  id: string;
  username: string;
  nickname?: string;
  email?: string;
  role?: string;
  avatar?: string;
  createdAt?: string;
}
