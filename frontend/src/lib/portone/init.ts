/**
 * PortOne (구 아임포트) SDK 초기화 유틸
 * CDN 스크립트를 동적으로 로드하고 중복 로딩을 방지합니다.
 * @see docs/frontend/04_auth_security.md 4.1절
 */

const PORTONE_SDK_URL = 'https://cdn.iamport.kr/v1/iamport.js';
const SCRIPT_ID = 'portone-sdk';

let loadPromise: Promise<void> | null = null;

/**
 * PortOne SDK를 동적으로 로드합니다.
 * 이미 로드 중이거나 로드 완료된 경우 기존 Promise를 반환하여 중복 로딩을 방지합니다.
 *
 * @returns SDK 로드 완료 Promise
 * @throws SDK 로드 실패 시 에러
 *
 * @example
 * ```ts
 * try {
 *   await loadPortOneSDK();
 *   if (window.IMP) {
 *     window.IMP.init('your_imp_code');
 *   }
 * } catch (error) {
 *   console.error('PortOne SDK 로드 실패:', error);
 * }
 * ```
 */
export async function loadPortOneSDK(): Promise<void> {
  // 이미 로드된 경우
  if (typeof window !== 'undefined' && window.IMP) {
    return Promise.resolve();
  }

  // 이미 로딩 중인 경우
  if (loadPromise) {
    return loadPromise;
  }

  // 새로운 로딩 시작
  loadPromise = new Promise<void>((resolve, reject) => {
    // 서버 사이드 렌더링 환경 체크
    if (typeof window === 'undefined') {
      reject(new Error('PortOne SDK는 브라우저 환경에서만 사용 가능합니다.'));
      return;
    }

    // 이미 스크립트 태그가 존재하는지 확인
    const existingScript = document.getElementById(SCRIPT_ID);
    if (existingScript) {
      // 스크립트는 있지만 IMP가 없는 경우 (로딩 중)
      existingScript.addEventListener('load', () => {
        if (window.IMP) {
          resolve();
        } else {
          reject(new Error('PortOne SDK 로드 완료되었으나 IMP 객체가 없습니다.'));
        }
      });
      existingScript.addEventListener('error', () => {
        reject(new Error('PortOne SDK 스크립트 로드에 실패했습니다.'));
      });
      return;
    }

    // 새로운 스크립트 태그 생성
    const script = document.createElement('script');
    script.id = SCRIPT_ID;
    script.src = PORTONE_SDK_URL;
    script.async = true;

    script.onload = () => {
      if (window.IMP) {
        resolve();
      } else {
        reject(new Error('PortOne SDK 로드 완료되었으나 IMP 객체가 없습니다.'));
      }
    };

    script.onerror = () => {
      reject(new Error('PortOne SDK 스크립트 로드에 실패했습니다.'));
    };

    document.head.appendChild(script);
  });

  return loadPromise;
}

/**
 * PortOne SDK 로드 상태 확인
 * @returns SDK 로드 완료 여부
 */
export function isPortOneLoaded(): boolean {
  return typeof window !== 'undefined' && !!window.IMP;
}
