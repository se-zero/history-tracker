export function projectMark(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return "??";
  const tokens = trimmed.split(/\s+/);
  if (tokens.length === 1) return tokens[0].slice(0, 2).toUpperCase();
  return (tokens[0][0] + tokens[1][0]).toUpperCase();
}
