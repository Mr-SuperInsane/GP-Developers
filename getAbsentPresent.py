from time import sleep
from playwright.sync_api import Playwright, sync_playwright
from bs4 import BeautifulSoup


def run(playwright: Playwright, email, password, class_n) -> None:
    browser = playwright.chromium.launch(headless=False)
    context = browser.new_context()
    page = context.new_page()
    page.goto("https://myportal.osakac.ac.jp/")
    page.get_by_role("button", name="トップ画面へ").click()
    sleep(0.3)
    page.get_by_role("textbox", name="メールアドレスまたは電話番号").fill(email)
    sleep(0.4)
    page.get_by_role("textbox", name="メールアドレスまたは電話番号").press("Enter")
    sleep(0.2)
    page.get_by_role("textbox", name="パスワードを入力").fill(password)
    sleep(0.5)
    page.get_by_role("textbox", name="パスワードを入力").press("Enter")
    page.locator("#form_content-j_idt336").click()
    # name="授業名"
    page.get_by_role("cell", name=class_n).click()
    sleep(2)
    html_content = page.content()
    context.close()
    browser.close()

    result = {
        "present":0,
        "absent":0,
        "detail":{
            
        }
    }
    soup = BeautifulSoup(html_content, "html.parser")
    for index, content in enumerate(soup.find_all("div", class_="contents_state")):
        try:
            if str(content.find_all("img")[0]["src"]) == "/ecp/web/img_ja/attend_icon_absence_s.gif":
                result["absent"] += 1
                result["detail"][f"no{index+1}"] = 1
            else:
                result["present"] += 1
                result["detail"][f"no{index+1}"] = 2
        except:
            print(f"現在は{index}回目までしか授業を受けていません")
            break

    return result

if __name__ == "__main__":
    
    EMAIL = "xx00x000@college.jp"
    PASSWORD = "UnchiBuriBuri"
    CLASS = "マイポータルに表示される授業名"

    with sync_playwright() as playwright:
        result = run(playwright, email=EMAIL, password=PASSWORD, class_n=CLASS)
    print(result)
