/**
 * PortOne (구 아임포트) 본인인증 SDK 타입 정의
 * @see https://portone.gitbook.io/docs/auth/guide
 */

/**
 * 본인인증 요청 파라미터
 */
export interface IMPCertificationParams {
  /** 가맹점 식별코드 */
  merchant_uid: string;
  /** 본인인증 방식 (phone: 휴대폰, card: 카드, cert: 공동인증서) */
  m_redirect_url?: string;
  /** 본인인증 팝업 여부 */
  popup?: boolean;
}

/**
 * 본인인증 응답 데이터
 */
export interface IMPCertificationResponse {
  /** 본인인증 성공 여부 */
  success: boolean;
  /** 본인인증 고유번호 (백엔드로 전달하여 CI/DI 추출) */
  imp_uid?: string;
  /** 가맹점 주문번호 */
  merchant_uid?: string;
  /** 에러 코드 */
  error_code?: string;
  /** 에러 메시지 */
  error_msg?: string;
}

/**
 * PortOne SDK 인터페이스
 */
export interface IMP {
  /** PortOne SDK 초기화 */
  init: (impCode: string) => void;
  /** 본인인증 요청 */
  certification: (
    params: IMPCertificationParams,
    callback?: (response: IMPCertificationResponse) => void,
  ) => void;
}

/**
 * Window 객체에 IMP 추가
 */
declare global {
  interface Window {
    IMP?: IMP;
  }
}

export {};
