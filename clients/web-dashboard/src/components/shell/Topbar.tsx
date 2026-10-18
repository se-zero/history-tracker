import { Fragment, type ReactNode } from "react";

import { AppLanguageMenu } from "@/components/shell/AppLanguageMenu";

interface Props {
  crumbs: string[];
  right?: ReactNode;
}

export function Topbar({ crumbs, right }: Props) {
  return (
    <div className="topbar">
      <div className="crumbs">
        {crumbs.map((c, i) => (
          <Fragment key={i}>
            {i > 0 && <span className="sep">/</span>}
            <span className={i === crumbs.length - 1 ? "current" : ""}>{c}</span>
          </Fragment>
        ))}
      </div>
      <div className="topbar-spacer" />
      {right}
      <AppLanguageMenu />
    </div>
  );
}
