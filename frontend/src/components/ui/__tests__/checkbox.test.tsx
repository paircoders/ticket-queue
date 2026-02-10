import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Checkbox } from '../checkbox'

describe('Checkbox', () => {
  it('renders as checkbox role', () => {
    render(<Checkbox />)
    expect(screen.getByRole('checkbox')).toBeInTheDocument()
  })

  it('toggles checked state on click', async () => {
    const user = userEvent.setup()
    const handleChange = jest.fn()
    render(<Checkbox onCheckedChange={handleChange} />)
    await user.click(screen.getByRole('checkbox'))
    expect(handleChange).toHaveBeenCalledWith(true)
  })

  it('can be unchecked', async () => {
    const user = userEvent.setup()
    const handleChange = jest.fn()
    render(<Checkbox defaultChecked onCheckedChange={handleChange} />)
    await user.click(screen.getByRole('checkbox'))
    expect(handleChange).toHaveBeenCalledWith(false)
  })

  it('supports disabled state', () => {
    render(<Checkbox disabled />)
    expect(screen.getByRole('checkbox')).toBeDisabled()
  })

  it('has correct aria-checked state', () => {
    render(<Checkbox checked={true} />)
    expect(screen.getByRole('checkbox')).toHaveAttribute('aria-checked', 'true')
  })
})
