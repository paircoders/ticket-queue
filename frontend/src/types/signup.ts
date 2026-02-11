/**
 * 회원가입 위저드 단계
 * 1: 약관동의
 * 2: CAPTCHA
 * 3: 본인인증
 * 4: 정보입력
 */
export type SignupStep = 1 | 2 | 3 | 4;

/**
 * 회원가입 위저드 데이터
 * 각 단계에서 수집한 데이터를 저장
 */
export interface SignupWizardData {
  /** 서비스 이용약관 동의 여부 */
  serviceTermsAgreed: boolean;
  /** 개인정보 수집 및 이용 동의 여부 */
  privacyTermsAgreed: boolean;
  /** Google reCAPTCHA 토큰 */
  recaptchaToken: string | null;
  /** 본인인증 CI (Connecting Information) */
  ci: string | null;
  /** 본인인증 DI (Duplication Information) */
  di: string | null;
}

/**
 * 회원가입 폼 데이터 (Step 4)
 */
export interface SignupFormData {
  email: string;
  password: string;
  passwordConfirm: string;
  name: string;
  phone: string;
}
