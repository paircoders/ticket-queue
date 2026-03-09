import { loginSchema } from '../auth'

describe('loginSchema', () => {
  it('유효한 이메일과 비밀번호를 통과시킨다', () => {
    const result = loginSchema.safeParse({
      email: 'test@example.com',
      password: 'password123',
    })

    expect(result.success).toBe(true)
  })

  it('잘못된 이메일 형식을 거부한다', () => {
    const result = loginSchema.safeParse({
      email: 'not-an-email',
      password: 'password123',
    })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0].message).toBe('올바른 이메일 형식을 입력해주세요.')
  })

  it('@가 없는 이메일을 거부한다', () => {
    const result = loginSchema.safeParse({
      email: 'testexample.com',
      password: 'password123',
    })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0].message).toBe('올바른 이메일 형식을 입력해주세요.')
  })

  it('빈 비밀번호를 거부한다', () => {
    const result = loginSchema.safeParse({
      email: 'test@example.com',
      password: '',
    })

    expect(result.success).toBe(false)
    expect(result.error?.issues[0].message).toBe('비밀번호를 입력해주세요.')
  })

  it('이메일과 비밀번호 모두 비어있으면 두 개의 에러를 반환한다', () => {
    const result = loginSchema.safeParse({
      email: '',
      password: '',
    })

    expect(result.success).toBe(false)
    expect(result.error?.issues).toHaveLength(2)
  })
})
