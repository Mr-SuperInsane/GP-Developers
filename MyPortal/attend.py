from playwright.sync_api import Playwright, sync_playwright
from time import sleep

class AttendanceButtonNotFoundError(Exception):
    def __init__(self, message="出席ボタンが見つかりませんでした。"):
        self.message = message
        super().__init__(self.message)

def run(playwright: Playwright, number, email, password) -> None:
    """
    number:str 出席コード
    email:str マイポータルのログイン用メールアドレス
    password:str マイポータルのログイン用パスワード
    """

    # headless; True:ブラウザを起動しない / False:ブラウザを起動する
    browser = playwright.chromium.launch(headless=False)
    context = browser.new_context()
    page = context.new_page()
    page.goto("https://myportal.osakac.ac.jp/")
    page.get_by_role("button", name="出席登録").click()
    sleep(0.5) # 時間を置かないとエラーになる場合がある
    page.get_by_role("textbox", name="メールアドレスまたは電話番号").fill(email)
    sleep(0.5)
    page.get_by_role("textbox", name="メールアドレスまたは電話番号").press("Enter")
    page.get_by_role("textbox", name="パスワードを入力").fill(password)
    sleep(0.6)
    page.get_by_role("textbox", name="パスワードを入力").press("Enter")
    page.get_by_role("textbox", name="出席コード").click()
    sleep(0.4)
    if page.get_by_role("textbox", name="出席コード"):
        page.get_by_role("textbox", name="出席コード").fill(str(number))
        page.get_by_role("button", name="登録").click()
        page.close()
        context.close()
        browser.close()
        return "successful"
    else:
        raise AttendanceButtonNotFoundError()


if __name__ == "__main__":
    
    NUMBER = "1234"
    EMAIL = "xx00x000@college.jp"
    PASSWORD = "unchiBuriBuri"

    try:
        with sync_playwright() as playwright:
            result = run(playwright, number=NUMBER, email=EMAIL, password=PASSWORD)
        if result == "successful":
            print("出席完了!!")
    except AttendanceButtonNotFoundError as e:
        print(e)
