export function Banner({ tone, title, details }: { tone: "ok" | "error"; title: string; details?: string[] }) {
  return (
    <div className={tone === "ok" ? "banner ok" : "banner error"} role={tone === "error" ? "alert" : "status"}>
      <p className="banner-title">{title}</p>
      {details && details.length > 0 ? (
        <ul>
          {details.map((detail, index) => (
            <li key={`${detail}-${index}`}>{detail}</li>
          ))}
        </ul>
      ) : null}
    </div>
  )
}
