import PortOne from '@portone/browser-sdk/v2'

function generateUUID(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // crypto.getRandomValues 기반 UUID v4 fallback (구형 브라우저 대응)
  const bytes = crypto.getRandomValues(new Uint8Array(16))
  bytes[6] = (bytes[6] & 0x0f) | 0x40 // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80 // RFC 4122 variant
  return [
    ...[bytes[0], bytes[1], bytes[2], bytes[3]].map(b => b.toString(16).padStart(2, '0')),
    '-',
    ...[bytes[4], bytes[5]].map(b => b.toString(16).padStart(2, '0')),
    '-',
    ...[bytes[6], bytes[7]].map(b => b.toString(16).padStart(2, '0')),
    '-',
    ...[bytes[8], bytes[9]].map(b => b.toString(16).padStart(2, '0')),
    '-',
    ...[bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15]].map(b => b.toString(16).padStart(2, '0')),
  ].join('')
}

export async function requestIdentityVerification(): Promise<string> {
  const storeId = process.env.NEXT_PUBLIC_PORTONE_STORE_ID
  if (!storeId) {
    throw new Error('PortOne Store ID가 설정되지 않았습니다.')
  }

  const channelKey = process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY
  if (!channelKey) {
    throw new Error('PortOne Channel Key가 설정되지 않았습니다.')
  }

  const identityVerificationId = `identity-${generateUUID()}`

  const response = await PortOne.requestIdentityVerification({
    storeId,
    identityVerificationId,
    channelKey,
  })

  if (response?.code !== undefined) {
    if (response.code === 'IDENTITY_VERIFICATION_CANCELLED') {
      throw new Error('본인인증이 취소되었습니다.')
    }
    throw new Error(response.message ?? '본인인증에 실패했습니다.')
  }

  return identityVerificationId
}
