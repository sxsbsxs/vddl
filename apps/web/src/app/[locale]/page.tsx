import {getTranslations} from "next-intl/server";
import Link from "next/link";
export default async function HomePage() {
  const t=await getTranslations("Home");
  return <main className="shell">
    <header className="topbar"><Link className="brand" href="/">VideoFetch Pro</Link>
      <nav aria-label="Language"><Link href="/zh-CN">简体中文</Link><Link href="/en">English</Link><Link href="/ja">日本語</Link></nav>
    </header>
    <section className="hero"><p className="eyebrow">MULTI-PLATFORM MEDIA WORKSPACE</p>
      <h1>{t("title")}</h1><p className="intro">{t("description")}</p>
      <form className="url-form" action="#" method="get">
        <label htmlFor="media-url">{t("urlLabel")}</label>
        <input id="media-url" name="url" type="url" placeholder="https://…" autoComplete="url"/>
        <button type="button" disabled>{t("comingSoon")}</button>
      </form><p className="notice">{t("notice")}</p>
    </section><footer>VideoFetch Pro · Authorized media workflows</footer>
  </main>;
}
