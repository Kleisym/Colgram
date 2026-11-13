#!/usr/bin/env python3
"""Read-only audit of requested proxy catalogs; never connects to listed proxies."""
import concurrent.futures
import datetime
import json
from pathlib import Path
import re
import urllib.request
import urllib.error

REPOS = ["Thordata/awesome-free-proxy-list", "ProxyScrape/free-proxy-list", "iplocate/free-proxy-list",
    "proxifly/free-proxy-list", "TheSpeedX/PROXY-List", "hproxy-com/free-proxy-list", "jibukeshi/proxy_share",
    "narkhedesam/Proxy-List-Scrapper", "VPSLabCloud/VPSLab-Free-Proxy-List", "saisuiu/Lionkings-Http-Proxys-Proxies",
    "sakha1370/OpenRay", "Zaeem20/FREE_PROXIES_LIST", "zloi-user/hideip.me", "jetkai/proxy-list",
    "bq2015/FreeProxies", "monosans/proxy-list", "mahdibland/V2RayAggregator"]
SITES = ["https://proxyscrape.com/free-proxy-list", "https://best-proxies.ru/proxylist/free/",
    "https://geonode.com/free-proxy-list", "https://2ip.io/ru/proxy/", "https://proxydb.net/",
    "https://proxiware.com/free-proxy-list", "https://nodemaven.com/free-proxy-list/"]


def fetch(url, maximum=2_000_000):
    request = urllib.request.Request(url, headers={"User-Agent": "Colgram-source-audit/1.0", "Accept": "*/*"})
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            body = response.read(maximum)
            return response.status, response.headers.get_content_type(), body
    except (OSError, urllib.error.HTTPError) as error:
        return getattr(error, "code", 0), "", str(error).encode()


def audit_repo(repo):
    url = "https://github.com/" + repo
    code, _, raw = fetch("https://api.github.com/repos/" + repo + "/git/trees/HEAD?recursive=1")
    item = {"source": url, "catalog_status": code, "endpoints": []}
    if code != 200:
        item["classification"] = "catalog_unavailable"
        return item
    tree = json.loads(raw).get("tree", [])
    candidates = []
    for entry in tree:
        path = entry["path"]
        lower = path.lower()
        if entry["type"] != "blob" or lower.endswith((".py", ".js", ".md", ".yml", ".yaml", ".go")):
            continue
        if any(part in lower for part in ("socks5", "http", "v2ray", "vless", "sub.txt")) and ("/" in path or path.endswith(".txt")):
            protocol = "socks5" if "socks5" in lower else "http" if "http" in lower else "subscription"
            score = (protocol == "subscription", "raw" not in lower and "protocols" not in lower, "online" not in lower, len(path))
            candidates.append((score, protocol, path))
    selected = []
    for _, protocol, path in sorted(candidates):
        if len(selected) >= 4: break
        if sum(x[0] == protocol for x in selected) >= 2: continue
        selected.append((protocol, path))
    for protocol, path in selected:
        endpoint = "https://raw.githubusercontent.com/" + repo + "/HEAD/" + urllib.parse.quote(path)
        status, content_type, data = fetch(endpoint, 65536)
        text = data.decode("utf-8", errors="replace")
        lines = text.splitlines()
        address_lines = sum(bool(re.match(r"^(?:(?:socks5|https?)://)?(?:\d{1,3}\.){3}\d{1,3}:\d+\b", line)) for line in lines)
        item["endpoints"].append({"url": endpoint, "status": status, "content_type": content_type,
            "protocol_hint": protocol, "sample_lines": len(lines), "address_like_lines": address_lines,
            "automatic_feed_candidate": status == 200 and address_lines > 0 and protocol != "subscription"})
    item["classification"] = "machine_readable" if any(x["automatic_feed_candidate"] for x in item["endpoints"]) else "subscription_or_custom_adapter"
    return item


def audit_site(url):
    code, mime, body = fetch(url, 32768)
    return {"source": url, "catalog_status": code, "content_type": mime,
            "classification": "html_catalog_requires_api_or_adapter", "endpoints": [],
            "note": "HTML is not imported as a SOCKS5 list; catalog status does not prove proxy availability."}


def main():
    with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:
        repos = list(pool.map(audit_repo, REPOS))
        sites = list(pool.map(audit_site, SITES))
    output = Path(__file__).resolve().parents[1] / "docs/proxy-source-audit.json"
    report = {"checked_at_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "method": "Unauthenticated HTTPS catalog and bounded raw response fetches; no proxy dialing.",
        "sources": repos + sites}
    output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for source in report["sources"]:
        candidates = sum(x["automatic_feed_candidate"] for x in source["endpoints"])
        print(source["catalog_status"], source["source"], source["classification"], "candidates=" + str(candidates))
    print("SOURCE AUDIT:", len(report["sources"]), "catalogs checked;", output)


if __name__ == "__main__": main()
