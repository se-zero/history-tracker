import type { CSSProperties, ReactNode } from "react";

interface IconProps {
  size?: number;
  className?: string;
  style?: CSSProperties;
}

interface IconOpts {
  sw?: number;
}

function makeIcon(path: ReactNode, opts: IconOpts = {}) {
  return function Icon({ size = 16, className, style }: IconProps) {
    return (
      <svg
        width={size}
        height={size}
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth={opts.sw ?? 1.6}
        strokeLinecap="round"
        strokeLinejoin="round"
        className={className}
        style={style}
      >
        {path}
      </svg>
    );
  };
}

export const Icons = {
  Chat: makeIcon(
    <path d="M2.5 4.5a2 2 0 0 1 2-2h7a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-3l-3 2.5v-2.5H4.5a2 2 0 0 1-2-2v-5z" />,
  ),
  Plug: makeIcon(
    <>
      <path d="M5 7V3.5M8 7V3.5" />
      <path d="M3.5 7h6V9a3 3 0 0 1-3 3v0a3 3 0 0 1-3-3V7z" />
      <path d="M6.5 12v2" />
    </>,
  ),
  Graph: makeIcon(
    <>
      <circle cx="3.5" cy="3.5" r="1.5" />
      <circle cx="12.5" cy="3.5" r="1.5" />
      <circle cx="8" cy="12" r="1.5" />
      <path d="M4.6 4.6 7 10.5M11.4 4.6 9 10.5M5 3.5h6" />
    </>,
  ),
  Settings: makeIcon(
    <>
      <circle cx="8" cy="8" r="2" />
      <path d="M8 1.5v1.7M8 12.8v1.7M3.4 3.4l1.2 1.2M11.4 11.4l1.2 1.2M1.5 8h1.7M12.8 8h1.7M3.4 12.6l1.2-1.2M11.4 4.6l1.2-1.2" />
    </>,
  ),
  Plus: makeIcon(<path d="M8 3v10M3 8h10" />),
  Send: makeIcon(<path d="M2 8 14 2.5 11 14l-3-5-6-1z" />),
  Search: makeIcon(
    <>
      <circle cx="7" cy="7" r="4" />
      <path d="M10 10l3.5 3.5" />
    </>,
  ),
  ChevronDown: makeIcon(<path d="M4 6l4 4 4-4" />),
  Sparkle: makeIcon(
    <path d="M8 2v3M8 11v3M2 8h3M11 8h3M4.4 4.4l1.4 1.4M10.2 10.2l1.4 1.4M4.4 11.6l1.4-1.4M10.2 5.8l1.4-1.4" />,
  ),
  Branch: makeIcon(
    <>
      <circle cx="4" cy="4" r="1.4" />
      <circle cx="4" cy="12" r="1.4" />
      <circle cx="12" cy="6" r="1.4" />
      <path d="M4 5.4v5.2M5.3 5.3a4 4 0 0 0 4 2.2" />
    </>,
  ),
  Refactor: makeIcon(
    <>
      <path d="M3 5h7a3 3 0 0 1 0 6H6" />
      <path d="M5 3 3 5l2 2M8 13l-2-2 2-2" />
    </>,
  ),
  Fire: makeIcon(
    <path d="M8 14c2.5 0 4.5-1.8 4.5-4.2 0-1.6-1-2.5-2-3.4-1.5-1.4-2-2.4-2-4.4-1.5 1.5-3 3-3 5.4 0 .8.3 1.6.8 2.2-1.3-.2-2.3-1-2.3-2.2C3 9.6 5 14 8 14z" />,
  ),
  People: makeIcon(
    <>
      <circle cx="6" cy="6" r="2" />
      <circle cx="11.5" cy="6.5" r="1.6" />
      <path d="M2.5 12.5c.5-1.8 2-3 3.5-3s3 1.2 3.5 3M10 12.5c.4-1.3 1.5-2.2 2.5-2.2" />
    </>,
  ),
  X: makeIcon(<path d="M4 4l8 8M12 4l-8 8" />),
  ArrowRight: makeIcon(<path d="M3 8h10M9 4l4 4-4 4" />),
  Expand: makeIcon(<path d="M3 3h4M3 3v4M13 13H9M13 13V9" />),
  ZoomIn: makeIcon(
    <>
      <circle cx="7" cy="7" r="4" />
      <path d="M10 10l3.5 3.5M7 5v4M5 7h4" />
    </>,
  ),
  ZoomOut: makeIcon(
    <>
      <circle cx="7" cy="7" r="4" />
      <path d="M10 10l3.5 3.5M5 7h4" />
    </>,
  ),
  Fit: makeIcon(<path d="M3 5V3h2M11 3h2v2M13 11v2h-2M5 13H3v-2" />),
  ExternalLink: makeIcon(<path d="M9 3h4v4M13 3 8 8M11 9v4H3V5h4" />),
  Sidebar: makeIcon(
    <>
      <rect x="2" y="3" width="12" height="10" rx="1.5" />
      <path d="M6 3v10" />
    </>,
  ),
  Sun: makeIcon(
    <>
      <circle cx="8" cy="8" r="2.5" />
      <path d="M8 1.5v1.5M8 13v1.5M1.5 8h1.5M13 8h1.5M3.5 3.5l1 1M11.5 11.5l1 1M3.5 12.5l1-1M11.5 4.5l1-1" />
    </>,
  ),
  Moon: makeIcon(<path d="M13 9.5A5.5 5.5 0 1 1 6.5 3a4.5 4.5 0 0 0 6.5 6.5z" />),
  Layers: makeIcon(
    <>
      <path d="M8 2 2 5l6 3 6-3-6-3z" />
      <path d="M2 8l6 3 6-3M2 11l6 3 6-3" />
    </>,
  ),
  Trash: makeIcon(
    <path d="M3 4.5h10M6.5 4.5V3a1 1 0 0 1 1-1h1a1 1 0 0 1 1 1v1.5M4.5 4.5v8a1 1 0 0 0 1 1h5a1 1 0 0 0 1-1v-8M7 7v4M9 7v4" />,
  ),
  Check: makeIcon(<path d="M3 8.5l3 3 7-7" />),
  Code: makeIcon(<path d="M6 4 2 8l4 4M10 4l4 4-4 4" />),
  Filter: makeIcon(<path d="M2.5 3.5h11l-4 5v4l-3 1.5v-5.5l-4-5z" />),
  More: makeIcon(
    <>
      <circle cx="3.5" cy="8" r="1.3" fill="currentColor" />
      <circle cx="8" cy="8" r="1.3" fill="currentColor" />
      <circle cx="12.5" cy="8" r="1.3" fill="currentColor" />
    </>,
    { sw: 0 },
  ),
  GitHub: makeIcon(
    <path
      d="M8 1.5C4.4 1.5 1.5 4.4 1.5 8c0 2.9 1.9 5.3 4.4 6.2.3.1.4-.1.4-.3v-1.2c-1.8.4-2.2-.8-2.2-.8-.3-.7-.7-.9-.7-.9-.6-.4 0-.4 0-.4.7 0 1 .7 1 .7.6 1 1.6.7 2 .6.1-.4.2-.7.4-.9-1.4-.2-2.9-.7-2.9-3.2 0-.7.3-1.3.7-1.7-.1-.2-.3-.9.1-1.8 0 0 .6-.2 1.8.7.5-.1 1.1-.2 1.6-.2.5 0 1.1.1 1.6.2 1.2-.8 1.8-.7 1.8-.7.3.9.1 1.6.1 1.8.4.5.7 1 .7 1.7 0 2.5-1.5 3-2.9 3.2.2.2.4.6.4 1.2v1.7c0 .2.1.4.4.3 2.6-.9 4.4-3.4 4.4-6.2 0-3.6-2.9-6.5-6.5-6.5z"
      fill="currentColor"
      stroke="none"
    />,
    { sw: 0 },
  ),
};
