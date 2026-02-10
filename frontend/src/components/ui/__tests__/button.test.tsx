import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Button } from "../button";

describe("Button", () => {
  it("renders children", () => {
    render(<Button>Click me</Button>);
    expect(
      screen.getByRole("button", { name: "Click me" }),
    ).toBeInTheDocument();
  });

  describe("variants", () => {
    it.each(["primary", "secondary", "outline", "ghost", "danger"] as const)(
      "renders %s variant",
      (variant) => {
        render(<Button variant={variant}>Button</Button>);
        const button = screen.getByRole("button");
        expect(button).toHaveAttribute("data-variant", variant);
      },
    );

    it("defaults to primary variant", () => {
      render(<Button>Button</Button>);
      expect(screen.getByRole("button")).toHaveAttribute(
        "data-variant",
        "primary",
      );
    });
  });

  describe("sizes", () => {
    it.each(["sm", "md", "lg"] as const)("renders %s size", (size) => {
      render(<Button size={size}>Button</Button>);
      const button = screen.getByRole("button");
      expect(button).toHaveAttribute("data-size", size);
    });

    it("defaults to md size", () => {
      render(<Button>Button</Button>);
      expect(screen.getByRole("button")).toHaveAttribute("data-size", "md");
    });
  });

  describe("loading state", () => {
    it("shows spinner when loading", () => {
      render(<Button loading>Submit</Button>);
      expect(screen.getByRole("status")).toBeInTheDocument();
    });

    it("disables button when loading", () => {
      render(<Button loading>Submit</Button>);
      expect(screen.getByRole("button")).toBeDisabled();
    });

    it("still renders children when loading", () => {
      render(<Button loading>Submit</Button>);
      expect(screen.getByRole("button")).toHaveTextContent("Submit");
    });
  });

  describe("disabled state", () => {
    it("disables button when disabled prop is true", () => {
      render(<Button disabled>Button</Button>);
      expect(screen.getByRole("button")).toBeDisabled();
    });
  });

  describe("onClick", () => {
    it("calls onClick when clicked", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(<Button onClick={handleClick}>Click</Button>);
      await user.click(screen.getByRole("button"));
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it("does not call onClick when disabled", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(
        <Button disabled onClick={handleClick}>
          Click
        </Button>,
      );
      await user.click(screen.getByRole("button"));
      expect(handleClick).not.toHaveBeenCalled();
    });

    it("does not call onClick when loading", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(
        <Button loading onClick={handleClick}>
          Click
        </Button>,
      );
      await user.click(screen.getByRole("button"));
      expect(handleClick).not.toHaveBeenCalled();
    });
  });

  it("accepts custom className", () => {
    render(<Button className="custom-class">Button</Button>);
    expect(screen.getByRole("button")).toHaveClass("custom-class");
  });
});
