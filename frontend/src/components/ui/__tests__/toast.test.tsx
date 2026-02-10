import { render } from "@testing-library/react";
import { Toaster } from "../sonner";

describe("Toaster", () => {
  it("renders without error", () => {
    const { container } = render(<Toaster />);
    expect(container).toBeInTheDocument();
  });
});
