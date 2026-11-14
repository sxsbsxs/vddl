import ipaddress
from urllib.parse import urlsplit, urlunsplit

class UnsafeSourceUrl(ValueError):
    pass

def validate_source_url(raw_url: str) -> str:
    """Reject malformed URLs and obvious non-public destinations.

    Provider adapters must additionally allow-list hostnames, validate DNS
    answers at connection time, and re-check each redirect.
    """
    try:
        parsed = urlsplit(raw_url.strip())
        hostname = parsed.hostname
        port = parsed.port
    except ValueError as exc:
        raise UnsafeSourceUrl("Invalid URL") from exc
    if parsed.scheme.lower() != "https":
        raise UnsafeSourceUrl("Only HTTPS source URLs are accepted")
    if not hostname or parsed.username is not None or parsed.password is not None:
        raise UnsafeSourceUrl("URL must contain a public hostname and no credentials")
    if port not in (None, 443):
        raise UnsafeSourceUrl("Non-standard ports are not accepted")
    if parsed.fragment:
        raise UnsafeSourceUrl("URL fragments are not accepted")
    hostname = hostname.rstrip(".").lower()
    if hostname in {"localhost", "localhost.localdomain"} or hostname.endswith(
        (".localhost", ".local", ".internal", ".test", ".invalid")
    ):
        raise UnsafeSourceUrl("Local or reserved destinations are not accepted")
    try:
        ipaddress.ip_address(hostname)
    except ValueError:
        pass
    else:
        raise UnsafeSourceUrl("IP-literal source URLs are not accepted")
    try:
        ascii_hostname = hostname.encode("idna").decode("ascii")
    except UnicodeError as exc:
        raise UnsafeSourceUrl("Invalid hostname") from exc
    if "." not in ascii_hostname or len(ascii_hostname) > 253:
        raise UnsafeSourceUrl("A fully-qualified public hostname is required")
    return urlunsplit(("https", ascii_hostname + (f":{port}" if port else ""), parsed.path or "/", parsed.query, ""))
