#!/usr/bin/env python3
"""Verify the packaged Flutter first-install and login flow in Chromium."""

from __future__ import annotations

import argparse
import json
import urllib.request

from playwright.sync_api import Error as PlaywrightError
from playwright.sync_api import expect, sync_playwright


ADMIN_EMAIL = "admin@biliwind.com"
ADMIN_PASSWORD = "admin@biliwind.com"


def install_status(base_url: str) -> dict[str, object]:
    with urllib.request.urlopen(f"{base_url}/api/v1/install/status", timeout=10) as response:
        if response.status != 200:
            raise AssertionError(f"install status returned HTTP {response.status}")
        payload = json.loads(response.read())
    if not isinstance(payload, dict):
        raise AssertionError("install status response was not an object")
    return payload


def type_replacing(field, value: str) -> None:
    field.click()
    field.page.wait_for_timeout(150)
    current = field.input_value()
    if current:
        field.press("Control+A")
        field.press("Backspace")
    field.press_sequentially(value, delay=60)
    expect(field).to_have_value(value)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default="http://127.0.0.1:18090")
    args = parser.parse_args()
    base_url = args.base_url.rstrip("/")
    if not base_url.startswith("http://127.0.0.1:"):
        parser.error("base URL must use the local 127.0.0.1 HTTP origin")

    if install_status(base_url).get("initialized") is not False:
        raise SystemExit("refusing to run: browser acceptance requires a fresh database")

    browser_errors: list[str] = []
    try:
        with sync_playwright() as playwright:
            browser = playwright.chromium.launch(headless=True)
            context = browser.new_context(viewport={"width": 1440, "height": 1000})
            page = context.new_page()
            page.on("pageerror", lambda error: browser_errors.append(str(error)))

            response = page.goto(base_url, wait_until="domcontentloaded")
            if response is None or response.status != 200:
                raise AssertionError(
                    f"Flutter page returned HTTP {response.status if response else 'no response'}"
                )

            # Flutter Web enables its semantics tree on demand. The activation
            # placeholder is intentionally one pixel outside the viewport, so
            # invoke its DOM click handler before using accessible locators.
            page.locator(
                "flt-semantics-placeholder[aria-label='Enable accessibility']"
            ).evaluate("element => element.click()")
            expect(page.get_by_text("开始监控你的服务", exact=True)).to_be_visible(timeout=30_000)
            api_address = page.get_by_placeholder("https://api.example.com")
            expect(api_address).to_have_value(base_url)
            fields = page.get_by_role("textbox")
            email = fields.nth(1)
            password = fields.nth(2)
            confirmation = fields.nth(3)
            project = fields.nth(4)
            for field in (email, password, confirmation):
                expect(field).to_have_value("")

            type_replacing(email, ADMIN_EMAIL)
            type_replacing(password, ADMIN_PASSWORD)
            type_replacing(confirmation, ADMIN_PASSWORD)
            page.get_by_text("创建管理员并进入控制台", exact=True).click()

            expect(page.get_by_text("运维控制台", exact=True)).to_be_visible(timeout=30_000)
            expect(page.get_by_text(ADMIN_EMAIL, exact=True)).to_be_visible()
            expect(project).to_have_count(0)
            if install_status(base_url).get("initialized") is not True:
                raise AssertionError("setup did not mark the application initialized")

            page.get_by_text("退出登录", exact=True).click()
            expect(page.get_by_text("欢迎回来", exact=True)).to_be_visible(timeout=10_000)
            login_fields = page.get_by_role("textbox")
            login_email = login_fields.nth(1)
            login_password = login_fields.nth(2)
            type_replacing(login_email, ADMIN_EMAIL)
            type_replacing(login_password, ADMIN_PASSWORD)
            page.get_by_text("登录控制台", exact=True).click()
            expect(page.get_by_text("运维控制台", exact=True)).to_be_visible(timeout=30_000)
            expect(page.get_by_text(ADMIN_EMAIL, exact=True)).to_be_visible()

            if browser_errors:
                raise AssertionError(f"browser reported page errors: {browser_errors}")
            print(
                "browser_setup=passed logout=passed relogin=passed "
                "admin=admin@biliwind.com"
            )
            context.close()
            browser.close()
    except PlaywrightError as error:
        raise SystemExit(f"browser setup acceptance failed: {error}") from error
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
