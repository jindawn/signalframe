export function ApiError({ text }: { text: string }) {
  return text ? (
    <p role="alert" className="error">
      {text}
    </p>
  ) : null;
}
