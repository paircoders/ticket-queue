import { render, screen } from "@testing-library/react";
import { Spinner } from "../spinner";

describe("Spinner", () => {
  it("renders with status role", () => {
    render(<Spinner />);
    expect(screen.getByRole("status")).toBeInTheDocument();
  });

  it("has accessible label", () => {
    render(<Spinner />);
    expect(screen.getByRole("status")).toHaveAttribute(
      "aria-label",
      "로딩 중",
    );
  });

  describe("size variants", () => {
    it("renders sm size", () => {
      render(<Spinner size="sm" />);
      const spinner = screen.getByRole("status");
      expect(spinner).toHaveClass("size-4");
    });

    it("renders md size (default)", () => {
      render(<Spinner />);
      const spinner = screen.getByRole("status");
      expect(spinner).toHaveClass("size-6");
    });

    it("renders lg size", () => {
      render(<Spinner size="lg" />);
      const spinner = screen.getByRole("status");
      expect(spinner).toHaveClass("size-8");
    });
  });

  it("applies custom className", () => {
    render(<Spinner className="text-primary" />);
    expect(screen.getByRole("status")).toHaveClass("text-primary");
  });
});
