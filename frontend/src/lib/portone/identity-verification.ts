import PortOne from '@portone/browser-sdk/v2'

export async function requestIdentityVerification(): Promise<string> {
  const storeId = process.env.NEXT_PUBLIC_PORTONE_STORE_ID
  if (!storeId) {
    throw new Error('PortOne Store ID가 설정되지 않았습니다.')
  }

  const channelKey = process.env.NEXT_PUBLIC_PORTONE_CHANNEL_KEY
  if (!channelKey) {
    throw new Error('PortOne Channel Key가 설정되지 않았습니다.')
  }

  const identityVerificationId = `identity-${crypto.randomUUID()}`

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
