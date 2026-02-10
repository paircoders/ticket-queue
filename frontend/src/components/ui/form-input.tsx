"use client";

import * as React from "react";

import { cn } from "@/lib/utils";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";

interface FormInputProps
  extends Omit<React.ComponentProps<"input">, "onChange"> {
  label?: string;
  error?: string;
  onChange?: (value: string) => void;
}

function FormInput({
  label,
  error,
  onChange,
  className,
  id: externalId,
  ...props
}: FormInputProps) {
  const generatedId = React.useId();
  const id = externalId ?? generatedId;
  const errorId = `${id}-error`;
  const hasError = !!error;

  const handleChange = React.useCallback(
    (e: React.ChangeEvent<HTMLInputElement>) => {
      onChange?.(e.target.value);
    },
    [onChange],
  );

  return (
    <div className={cn("grid gap-2", className)}>
      {label && <Label htmlFor={id}>{label}</Label>}
      <Input
        id={id}
        aria-invalid={hasError}
        aria-describedby={hasError ? errorId : undefined}
        onChange={handleChange}
        {...props}
      />
      {hasError && (
        <p id={errorId} className="text-sm text-destructive" role="alert">
          {error}
        </p>
      )}
    </div>
  );
}

export { FormInput };
export type { FormInputProps };
