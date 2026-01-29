#!/usr/bin/env python3
"""
Seeder that fetches Irish Courts legal diary data and populates the lists API.

Each diary entry becomes a list with metadata (date, venue, type).
Each line from the diary detail page becomes an item in that list.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import sys
import time
from dataclasses import dataclass, asdict
from datetime import datetime, timedelta
from pathlib import Path
from typing import List, Optional, Tuple
from urllib.parse import urljoin

import requests
from bs4 import BeautifulSoup, Tag

BASE_URL = "https://legaldiary.courts.ie"
USER_AGENT = "claude-lists-seeder/1.0"
CACHE_DIR = Path(os.getenv("CACHE_DIR", "/cache"))


def get_listing_url() -> str:
    """Build listing URL with dateFrom set to yesterday."""
    yesterday = (datetime.now() - timedelta(days=1)).strftime("%d-%m-%Y")
    return f"{BASE_URL}/legaldiary.nsf/circuit-court?OpenView&Jurisdiction=circuit-court&area=&type=&dateType=Range&dateFrom={yesterday}&dateTo=&text="


def url_to_cache_path(url: str) -> Path:
    """Convert URL to a cache file path."""
    url_hash = hashlib.md5(url.encode()).hexdigest()
    return CACHE_DIR / f"{url_hash}.html"


def get_cached_html(url: str, max_age_hours: int = 0) -> Optional[str]:
    """Get HTML from cache if it exists and is not expired.

    Args:
        max_age_hours: Maximum age in hours. 0 means never expire.
    """
    cache_path = url_to_cache_path(url)
    if not cache_path.exists():
        return None

    # Check age if expiry is set
    if max_age_hours > 0:
        mtime = datetime.fromtimestamp(cache_path.stat().st_mtime)
        if datetime.now() - mtime > timedelta(hours=max_age_hours):
            return None

    return cache_path.read_text(encoding="utf-8")


def save_to_cache(url: str, html: str) -> None:
    """Save HTML to cache."""
    CACHE_DIR.mkdir(parents=True, exist_ok=True)
    cache_path = url_to_cache_path(url)
    cache_path.write_text(html, encoding="utf-8")


@dataclass
class DiaryEntry:
    """A single diary entry from the listing page."""
    date_text: str
    date_sort: str  # yyyymmdd
    venue: str
    type: str
    subtitle: str
    updated_text: str
    updated_sort: str
    url: str


def wait_for_service(url: str, headers: dict, timeout: int = 120) -> None:
    """Wait for a service to become available."""
    start = time.time()
    while time.time() - start < timeout:
        try:
            r = requests.get(url, headers=headers, timeout=5)
            if r.status_code < 500:
                return
        except requests.RequestException:
            pass
        time.sleep(2)
    raise RuntimeError(f"Service {url} not ready after {timeout}s")


def get_keycloak_token(keycloak_url: str, realm: str, client_id: str,
                       username: str, password: str) -> str:
    """Get an access token from Keycloak."""
    token_url = f"{keycloak_url}/realms/{realm}/protocol/openid-connect/token"

    # Wait for Keycloak
    print("Waiting for Keycloak...")
    wait_for_service(f"{keycloak_url}/realms/{realm}", {})
    print("Keycloak is ready")

    r = requests.post(token_url, data={
        "grant_type": "password",
        "client_id": client_id,
        "username": username,
        "password": password,
    }, timeout=30)
    r.raise_for_status()
    return r.json()["access_token"]


def fetch_html(session: requests.Session, url: str, timeout: int = 30, use_cache: bool = True, cache_hours: int = 24) -> str:
    """Fetch HTML from a URL, using cache if available."""
    if use_cache:
        cached = get_cached_html(url, cache_hours)
        if cached:
            return cached

    r = session.get(url, timeout=timeout)
    r.raise_for_status()
    if not r.encoding:
        r.encoding = "utf-8"
    html = r.text

    if use_cache:
        save_to_cache(url, html)

    return html


def parse_listing(html: str, base_url: str) -> List[DiaryEntry]:
    """Parse the diary listing page and extract entries."""
    soup = BeautifulSoup(html, "lxml")
    results_div = soup.select_one("#searchResults")
    if results_div is None:
        raise RuntimeError("Could not find #searchResults in listing page")

    entries: List[DiaryEntry] = []
    for tr in results_div.select("tr.clickable-row"):
        tds = tr.find_all("td")
        if len(tds) < 5:
            continue
        entries.append(DiaryEntry(
            date_text=tds[0].get_text(strip=True),
            date_sort=(tds[0].get("data-text") or "").strip(),
            venue=tds[1].get_text(strip=True),
            type=tds[2].get_text(strip=True),
            subtitle=tds[3].get_text(strip=True),
            updated_text=tds[4].get_text(strip=True),
            updated_sort=(tds[4].get("data-text") or "").strip(),
            url=urljoin(base_url, (tr.get("data-url") or "").strip()),
        ))
    return entries


def parse_detail_lines(html: str) -> List[str]:
    """Extract text lines from a diary detail page's .ld-content div."""
    soup = BeautifulSoup(html, "lxml")
    divs = soup.select(".ld-content")
    if not divs:
        return []

    div = divs[0]
    working = BeautifulSoup(str(div), "lxml").select_one(".ld-content")
    for br in working.find_all("br"):
        br.replace_with("\n")

    lines: List[str] = []
    blocks = [el for el in working.descendants if isinstance(el, Tag) and el.name in ("p", "li")]
    if blocks:
        for b in blocks:
            txt = b.get_text(separator="\n", strip=True)
            for line in txt.splitlines():
                s = line.strip()
                if s:
                    lines.append(s)
    else:
        txt = working.get_text(separator="\n", strip=True)
        for line in txt.splitlines():
            s = line.strip()
            if s:
                lines.append(s)
    return lines


def yyyymmdd_to_iso(s: str) -> Optional[str]:
    """Convert yyyymmdd to ISO date string."""
    if not s:
        return None
    try:
        return datetime.strptime(s, "%Y%m%d").date().isoformat()
    except ValueError:
        return None


import re

# Patterns for parsing case items
CASE_NUMBER_PATTERN = re.compile(r'([A-Z]{2,4}[DP]?\d+/\d{4}|\d{4}/[A-Z]*\d+)')
LIST_NUMBER_PATTERN = re.compile(r'^(\d+)\s+')
PARTIES_PATTERN = re.compile(r'\s+[-–]\s*[Vv]\s+[-–]?\s+|\s+[Vv]\s+')  # " - V - " or " v "
CASE_TYPES = ['Equity', 'Personal Injury', 'Contract', 'Tort', 'Family', 'Probate', 'Landlord', 'Appeal']


def parse_case_item(line: str) -> dict:
    """Parse a line item and extract structured case data.

    Returns a dict with:
        is_case: bool - whether this is a case (vs header/metadata)
        list_number: int or None - the list number (1, 2, 3...)
        case_number: str or None - the case reference (e.g., 2023/00078)
        parties: str or None - the parties involved
        case_type: str or None - type of case (Equity, Personal Injury, etc.)
        raw: str - the original line
    """
    result = {
        "is_case": False,
        "list_number": None,
        "case_number": None,
        "parties": None,
        "case_type": None,
        "raw": line,
    }

    # Check for list number at start
    list_match = LIST_NUMBER_PATTERN.match(line)
    if not list_match:
        return result

    result["list_number"] = int(list_match.group(1))
    remainder = line[list_match.end():].strip()

    # Check for case number
    case_match = CASE_NUMBER_PATTERN.search(remainder)
    if not case_match:
        return result

    # This is a case
    result["is_case"] = True
    result["case_number"] = case_match.group(1)

    # Extract parties - text after case number, before case type or solicitors
    after_case_num = remainder[case_match.end():].strip()

    # Remove leading tab/colon
    after_case_num = after_case_num.lstrip('\t:').strip()

    # Check for case type
    for ct in CASE_TYPES:
        if ct in after_case_num:
            result["case_type"] = ct
            # Parties are before the case type
            parts = after_case_num.split(ct)
            if parts[0].strip():
                result["parties"] = parts[0].strip().rstrip('\t:').strip()
            break

    # If no case type found, parties might be the whole thing before solicitors
    if not result["parties"] and after_case_num:
        # Split on colon or tab which often separates parties from solicitors
        parts = re.split(r'[:\t]', after_case_num, maxsplit=1)
        if parts[0].strip():
            result["parties"] = parts[0].strip()

    # Clean up parties - remove trailing punctuation
    if result["parties"]:
        result["parties"] = result["parties"].rstrip(':,\t ').strip()

    return result


def create_list(session: requests.Session, api_url: str, entry: DiaryEntry, headers: List[str] = None) -> int:
    """Create a list from a diary entry, return the list ID."""
    metadata = {
        "date": yyyymmdd_to_iso(entry.date_sort),
        "date_text": entry.date_text,
        "venue": entry.venue,
        "type": entry.type,
        "subtitle": entry.subtitle,
        "updated": yyyymmdd_to_iso(entry.updated_sort),
        "source_url": entry.url,
    }

    # Include headers (court info, judge, etc.) in metadata
    if headers:
        metadata["headers"] = headers

    # Use subtitle as name, or fallback to venue + date
    name = entry.subtitle if entry.subtitle else f"{entry.venue} - {entry.date_text}"
    description = f"{entry.type} - {entry.venue}" if entry.type else entry.venue

    payload = {
        "name": name,
        "description": description,
        "metadata": metadata,
    }

    r = session.post(
        f"{api_url}/lists",
        json=payload,
        headers={"Prefer": "return=representation"},
        timeout=30,
    )
    r.raise_for_status()
    return r.json()[0]["id"]


def parse_lines(lines: List[str]) -> Tuple[List[dict], List[dict]]:
    """Parse lines into cases and headers with position info.

    Returns tuple of (cases, headers).
    - cases: list of dicts with case metadata
    - headers: list of dicts with text and position (before which case number)
    """
    cases = []
    headers = []
    pending_headers = []  # Headers waiting to be assigned to next case

    for line in lines:
        parsed = parse_case_item(line)
        if parsed["is_case"]:
            list_num = parsed.get("list_number", len(cases) + 1)
            # Assign pending headers to this case
            for h in pending_headers:
                headers.append({"text": h, "before_case": list_num})
            pending_headers = []

            # Build metadata dict (exclude raw and None values)
            metadata = {k: v for k, v in parsed.items() if k != "raw" and v is not None}
            cases.append({
                "title": line,
                "metadata": metadata,
            })
        else:
            pending_headers.append(line)

    # Any remaining headers go at the end (after all cases)
    for h in pending_headers:
        headers.append({"text": h, "after_cases": True})

    return cases, headers


def create_items(session: requests.Session, api_url: str, list_id: int, cases: List[dict]) -> int:
    """Create items for a list from parsed cases. Returns count created."""
    if not cases:
        return 0

    items = [{
        "list_id": list_id,
        "title": case["title"],
        "done": False,
        "metadata": case["metadata"],
    } for case in cases]

    r = session.post(
        f"{api_url}/items",
        json=items,
        timeout=30,
    )
    r.raise_for_status()
    return len(items)


def main():
    parser = argparse.ArgumentParser(description="Seed lists API from courts.ie legal diary")
    parser.add_argument("--api-url", default=os.getenv("API_URL", "http://postgrest:3000"))
    parser.add_argument("--keycloak-url", default=os.getenv("KEYCLOAK_URL", "http://keycloak:8080"))
    parser.add_argument("--realm", default="claude-lists")
    parser.add_argument("--client-id", default="claude-lists-web")
    parser.add_argument("--username", default="admin")
    parser.add_argument("--password", default="admin")
    parser.add_argument("--listing-url", default=None)
    parser.add_argument("--delay", type=float, default=0.5, help="Delay between fetches (seconds)")
    parser.add_argument("--limit", type=int, default=0, help="Limit number of entries to process (0=all)")
    parser.add_argument("--timeout", type=int, default=30)
    parser.add_argument("--cache-hours", type=int, default=0, help="Cache expiry in hours (0=never expire)")
    parser.add_argument("--refresh", action="store_true", help="Force refresh from courts.ie (bypass cache)")
    args = parser.parse_args()

    # Get auth token
    print("Getting access token...")
    token = get_keycloak_token(
        args.keycloak_url, args.realm, args.client_id,
        args.username, args.password
    )
    print("Got access token")

    # Set up session for API calls
    api_session = requests.Session()
    api_session.headers.update({
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    })

    # Wait for API
    print("Waiting for API...")
    wait_for_service(args.api_url, dict(api_session.headers))
    print("API is ready")

    # Check if data already exists
    r = api_session.get(f"{args.api_url}/lists", timeout=30)
    r.raise_for_status()
    existing = r.json()
    if existing:
        print(f"Data already exists ({len(existing)} lists), skipping seed")
        return

    # Set up session for courts.ie
    courts_session = requests.Session()
    courts_session.headers.update({"User-Agent": USER_AGENT})

    # Cache settings
    use_cache = not args.refresh
    cache_hours = args.cache_hours
    if args.refresh:
        print("Refresh mode: bypassing cache, fetching fresh data from courts.ie")
    else:
        expiry = f"{cache_hours}h" if cache_hours > 0 else "never"
        print(f"Cache enabled (dir: {CACHE_DIR}, expiry: {expiry})")

    # Fetch and parse listing
    listing_url = args.listing_url or get_listing_url()
    print(f"Fetching listing from {listing_url}...")
    listing_html = fetch_html(courts_session, listing_url, args.timeout, use_cache, cache_hours)
    entries = parse_listing(listing_html, BASE_URL)
    print(f"Found {len(entries)} diary entries")

    if args.limit > 0:
        entries = entries[:args.limit]
        print(f"Limited to {len(entries)} entries")

    # Process each entry
    total_lists = 0
    total_cases = 0
    total_headers = 0
    for i, entry in enumerate(entries, 1):
        try:
            # Fetch detail page first to get headers
            if use_cache and get_cached_html(entry.url, cache_hours):
                detail_html = fetch_html(courts_session, entry.url, args.timeout, use_cache, cache_hours)
            else:
                time.sleep(args.delay)
                detail_html = fetch_html(courts_session, entry.url, args.timeout, use_cache, cache_hours)

            lines = parse_detail_lines(detail_html)
            cases, headers = parse_lines(lines)

            # Create the list with headers in metadata
            list_id = create_list(api_session, args.api_url, entry, headers)
            total_lists += 1
            total_headers += len(headers)

            # Create items only for cases
            cases_created = create_items(api_session, args.api_url, list_id, cases)
            total_cases += cases_created

            print(f"[{i}/{len(entries)}] {entry.venue} - {entry.date_text}: {cases_created} cases, {len(headers)} headers")

        except Exception as e:
            print(f"[{i}/{len(entries)}] ERROR processing {entry.url}: {e}", file=sys.stderr)
            continue

        time.sleep(args.delay)

    print(f"Seeding complete: {total_lists} lists, {total_cases} cases, {total_headers} headers")


if __name__ == "__main__":
    main()
