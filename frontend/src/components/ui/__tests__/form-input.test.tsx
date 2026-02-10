import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { FormInput } from "../form-input";

describe("FormInput", () => {
  it("renders with label", () => {
    render(<FormInput label="Email" />);
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
  });

  it("renders without label", () => {
    render(<FormInput placeholder="Enter text" />);
    expect(screen.getByPlaceholderText("Enter text")).toBeInTheDocument();
  });

  it("displays error message", () => {
    render(<FormInput label="Email" error="Invalid email" />);
    expect(screen.getByRole("alert")).toHaveTextContent("Invalid email");
  });

  it("does not display error when not provided", () => {
    render(<FormInput label="Email" />);
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("calls onChange with value string", async () => {
    const user = userEvent.setup();
    const handleChange = jest.fn();
    render(<FormInput label="Name" onChange={handleChange} />);
    await user.type(screen.getByLabelText("Name"), "hello");
    expect(handleChange).toHaveBeenCalledTimes(5);
    expect(handleChange).toHaveBeenNthCalledWith(1, "h");
    expect(handleChange).toHaveBeenNthCalledWith(2, "he");
    expect(handleChange).toHaveBeenNthCalledWith(3, "hel");
    expect(handleChange).toHaveBeenNthCalledWith(4, "hell");
    expect(handleChange).toHaveBeenNthCalledWith(5, "hello");
  });

  describe("accessibility", () => {
    it("sets aria-invalid when error exists", () => {
      render(<FormInput label="Email" error="Required" />);
      expect(screen.getByLabelText("Email")).toHaveAttribute(
        "aria-invalid",
        "true",
      );
    });

    it("sets aria-describedby linking to error", () => {
      render(<FormInput label="Email" error="Required" />);
      const input = screen.getByLabelText("Email");
      const errorEl = screen.getByRole("alert");
      expect(input).toHaveAttribute("aria-describedby", errorEl.id);
    });

    it("does not set aria-invalid when no error", () => {
      render(<FormInput label="Email" />);
      expect(screen.getByLabelText("Email")).not.toHaveAttribute(
        "aria-invalid",
        "true",
      );
    });
  });

  it("supports disabled state", () => {
    render(<FormInput label="Email" disabled />);
    expect(screen.getByLabelText("Email")).toBeDisabled();
  });
});
