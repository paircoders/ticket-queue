import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Modal } from '../modal'

describe('Modal', () => {
  it('renders children when open', () => {
    render(
      <Modal isOpen={true} onClose={jest.fn()}>
        <p>Modal content</p>
      </Modal>,
    )
    expect(screen.getByText('Modal content')).toBeInTheDocument()
  })

  it('does not render children when closed', () => {
    render(
      <Modal isOpen={false} onClose={jest.fn()}>
        <p>Modal content</p>
      </Modal>,
    )
    expect(screen.queryByText('Modal content')).not.toBeInTheDocument()
  })

  it('renders title', () => {
    render(
      <Modal isOpen={true} onClose={jest.fn()} title="Confirm">
        <p>Content</p>
      </Modal>,
    )
    expect(screen.getByText('Confirm')).toBeInTheDocument()
  })

  it('renders description', () => {
    render(
      <Modal isOpen={true} onClose={jest.fn()} title="Title" description="Some description">
        <p>Content</p>
      </Modal>,
    )
    expect(screen.getByText('Some description')).toBeInTheDocument()
  })

  it('renders footer', () => {
    render(
      <Modal isOpen={true} onClose={jest.fn()} footer={<button>Confirm</button>}>
        <p>Content</p>
      </Modal>,
    )
    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument()
  })

  it('calls onClose when close button is clicked', async () => {
    const user = userEvent.setup()
    const handleClose = jest.fn()
    render(
      <Modal isOpen={true} onClose={handleClose} title="Test">
        <p>Content</p>
      </Modal>,
    )
    const closeButton = screen.getByRole('button', { name: 'Close' })
    await user.click(closeButton)
    expect(handleClose).toHaveBeenCalledTimes(1)
  })
})
