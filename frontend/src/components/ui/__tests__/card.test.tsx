import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import {
  Card,
  CardHeader,
  CardTitle,
  CardDescription,
  CardContent,
  CardFooter,
} from "../card";

describe("Card", () => {
  it("renders children", () => {
    render(
      <Card>
        <CardContent>Card body</CardContent>
      </Card>,
    );
    expect(screen.getByText("Card body")).toBeInTheDocument();
  });

  it("applies custom className", () => {
    render(
      <Card className="test-class" data-testid="card">
        Content
      </Card>,
    );
    expect(screen.getByTestId("card")).toHaveClass("test-class");
  });

  it("renders full card structure", () => {
    render(
      <Card>
        <CardHeader>
          <CardTitle>Title</CardTitle>
          <CardDescription>Description</CardDescription>
        </CardHeader>
        <CardContent>Body</CardContent>
        <CardFooter>Footer</CardFooter>
      </Card>,
    );
    expect(screen.getByText("Title")).toBeInTheDocument();
    expect(screen.getByText("Description")).toBeInTheDocument();
    expect(screen.getByText("Body")).toBeInTheDocument();
    expect(screen.getByText("Footer")).toBeInTheDocument();
  });

  describe("clickable card", () => {
    it("calls onClick when clicked", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(<Card onClick={handleClick}>Clickable</Card>);
      await user.click(screen.getByRole("button"));
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it("has role=button when clickable", () => {
      render(<Card onClick={jest.fn()}>Clickable</Card>);
      expect(screen.getByRole("button")).toBeInTheDocument();
    });

    it("does not have role=button when not clickable", () => {
      render(<Card>Static</Card>);
      expect(screen.queryByRole("button")).not.toBeInTheDocument();
    });

    it("responds to Enter key", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(<Card onClick={handleClick}>Clickable</Card>);
      screen.getByRole("button").focus();
      await user.keyboard("{Enter}");
      expect(handleClick).toHaveBeenCalledTimes(1);
    });

    it("responds to Space key", async () => {
      const user = userEvent.setup();
      const handleClick = jest.fn();
      render(<Card onClick={handleClick}>Clickable</Card>);
      screen.getByRole("button").focus();
      await user.keyboard(" ");
      expect(handleClick).toHaveBeenCalledTimes(1);
    });
  });
});
