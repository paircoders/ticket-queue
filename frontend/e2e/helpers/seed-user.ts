/**
 * E2E 테스트용 유저 시딩 헬퍼
 *
 * 백엔드 EncryptionUtils / HashUtils와 동일한 방식으로 데이터를 생성합니다:
 *   - AES-256-GCM: Base64(IV[12] + CipherText + AuthTag)
 *   - HMAC-SHA256: Base64(HMAC(text, Base64-decoded salt))
 *   - BCrypt: bcryptjs (rounds=10)
 */

import * as crypto from 'crypto'
import { Client } from 'pg'
import * as bcrypt from 'bcryptjs'

// docker/secrets/ 에서 가져온 실제 로컬 환경 암호화 키
const ENC_SECRET_KEY = 'AUlt1REzHFuxIT6yvpbmwSI7CZy78lL5FENfLnwpRV4='
const ENC_HASH_SALT = 'rKad3kiRzNWXVSvVa7mhxqXNVQqxNfKVSj2Jk6V7cGg='

// 테스트 계정 정보 (로그인 테스트에서도 동일하게 사용)
export const TEST_USER = {
  email: 'e2e-test@example.com',
  password: 'Test1234!',
  name: 'E2E테스터',
  phone: '010-9999-8888',
}

/**
 * HMAC-SHA256 해싱 (백엔드 HashUtils.hash() 와 동일)
 * - key: Base64 디코딩
 * - 결과: Base64 인코딩
 */
function hmacSha256(text: string, saltBase64: string): string {
  const keyBytes = Buffer.from(saltBase64, 'base64')
  const mac = crypto.createHmac('sha256', keyBytes)
  mac.update(text, 'utf8')
  return mac.digest('base64')
}

/**
 * AES-256-GCM 암호화 (백엔드 EncryptionUtils.encrypt() 와 동일)
 * - 결과 포맷: Base64(IV[12] + CipherText + AuthTag[16])
 * - Java GCM은 cipherText 끝에 authTag가 붙어 있음
 */
function aesGcmEncrypt(plainText: string, keyBase64: string): string {
  const keyBytes = Buffer.from(keyBase64, 'base64')
  const iv = crypto.randomBytes(12)
  const cipher = crypto.createCipheriv('aes-256-gcm', keyBytes, iv)

  const encrypted = Buffer.concat([cipher.update(plainText, 'utf8'), cipher.final()])
  const authTag = cipher.getAuthTag() // 16 bytes

  // Java: ByteBuffer.put(iv) + ByteBuffer.put(cipherText) → cipherText에 authTag 포함
  const combined = Buffer.concat([iv, encrypted, authTag])
  return combined.toString('base64')
}

/**
 * DB에 테스트 유저를 삽입합니다.
 * 이미 존재하면 스킵합니다 (이메일 해시 기준).
 */
export async function seedTestUser(): Promise<void> {
  const { email, password, name, phone } = TEST_USER

  const passwordHash = await bcrypt.hash(password, 10)
  const emailHash = hmacSha256(email, ENC_HASH_SALT)
  const phoneHash = hmacSha256(phone, ENC_HASH_SALT)

  const emailEncrypted = aesGcmEncrypt(email, ENC_SECRET_KEY)
  const nameEncrypted = aesGcmEncrypt(name, ENC_SECRET_KEY)
  const phoneEncrypted = aesGcmEncrypt(phone, ENC_SECRET_KEY)

  const client = new Client({
    host: '192.168.50.111',
    port: 5432,
    database: 'ticket_queue',
    user: 'user_svc_user',
    password: 'user@1234',
    options: '-c search_path=user_service,common',
  })

  try {
    await client.connect()

    const existing = await client.query(
      'SELECT id FROM user_service.users WHERE email_hash = $1',
      [emailHash],
    )

    if (existing.rows.length === 0) {
      await client.query(
        `INSERT INTO user_service.users
           (email, email_hash, password_hash, name, phone, phone_hash, role, status)
         VALUES ($1, $2, $3, $4, $5, $6, 'USER', 'ACTIVE')`,
        [emailEncrypted, emailHash, passwordHash, nameEncrypted, phoneEncrypted, phoneHash],
      )
      console.log(`[seed] 테스트 유저 생성 완료: ${email}`)
    } else {
      console.log(`[seed] 테스트 유저 이미 존재, 스킵: ${email}`)
    }
  } catch (err) {
    console.warn('[seed] DB 시딩 실패 (백엔드 미실행 상태일 수 있음):', (err as Error).message)
  } finally {
    await client.end().catch(() => {})
  }
}
