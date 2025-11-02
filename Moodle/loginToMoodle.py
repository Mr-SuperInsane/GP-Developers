from __future__ import annotations

import json
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone

import requests

# ====== 設定項目 ======
MOODLE_URL   = "https://moodle2025.mc2.osakac.ac.jp/2025"
LOGIN_URL     = f"{MOODLE_URL}/login/index.php"
DASHBOARD_URL = f"{MOODLE_URL}/my/"


@dataclass
class SessionData:
    """ログイン後の HTML と維持された requests.Session をひとまとめに。"""
    html: str
    session: requests.Session


class MoodleClientError(RuntimeError):
    """Moodle クライアントで発生したエラーの基底クラス。"""


def login_and_get_session(username, password) -> SessionData:
    """
    Moodle にログインし、最終リダイレクト先の HTML と
    Cookie を保持した requests.Session を返す。
    GAS 版の loginAndGetSession() に相当。
    """
    sess = requests.Session()
    sess.headers.update({"User-Agent": "Python Moodle Client"})

    r = sess.get(LOGIN_URL, timeout=30)
    r.raise_for_status()

    m = re.search(r'<input type="hidden" name="logintoken" value="([^"]+)"', r.text)
    if not m:
        raise MoodleClientError("logintoken が見つかりませんでした。")
    logintoken = m.group(1)

    payload = {
        "logintoken": logintoken,
        "username": username,
        "password": password,
        "anchor": "", # 必須
    }
    r = sess.post(LOGIN_URL, data=payload, allow_redirects=False, timeout=30)
    if r.status_code != 303:
        raise MoodleClientError(f"ログイン POST に失敗しました (status={r.status_code})")

    next_url = r.headers.get("Location")
    for _ in range(5):
        if not next_url:
            raise MoodleClientError("リダイレクト先が取得できませんでした。")

        r = sess.get(next_url, allow_redirects=False, timeout=30)
        # 200 であれば最終到達
        if r.status_code == 200:
            return SessionData(html=r.text, session=sess)

        next_url = r.headers.get("Location")

    raise MoodleClientError("リダイレクト回数が上限を超えました。")


def get_assignments_true_ending(username, password, return_data=False):
    """
    MoodleのタイムラインAPIから、今後60日以内の課題を取得する。
    return_data=True の場合はリストで返す。
    """
    try:
        sd = login_and_get_session(username, password)

        m = re.search(r'"sesskey":"([^"]+)"', sd.html)
        if not m:
            raise MoodleClientError("sesskey が見つかりませんでした。")
        sesskey = m.group(1)

        method_name = "core_calendar_get_action_events_by_timesort"
        ajax_url = f"{MOODLE_URL}/lib/ajax/service.php?sesskey={sesskey}&info={method_name}"

        now = datetime.now(timezone.utc)
        future = now + timedelta(days=60)

        ajax_payload = [{
            "index": 0,
            "methodname": method_name,
            "args": {
                "limitnum": 50,
                "timesortfrom": int(now.timestamp()),
                "timesortto": int(future.timestamp()),
                "limittononsuspendedevents": True,
            }
        }]

        headers = {
            "Content-Type": "application/json",
            "Referer": DASHBOARD_URL,
        }

        r = sd.session.post(ajax_url, data=json.dumps(ajax_payload), headers=headers, timeout=30)
        r.raise_for_status()
        j = r.json()

        if j[0].get("error"):
            msg = j[0]["exception"]["message"]
            raise MoodleClientError(f"API からエラー応答: {msg}")

        events = j[0]["data"]["events"]
        if not events:
            if return_data:
                return []
            print("すべき課題はありません！")
            return

        JST = timezone(timedelta(hours=9))
        assignments = []

        for ev in events:
            dt = datetime.fromtimestamp(ev["timesort"], JST)
            remain_hours = int((dt - datetime.now(JST)).total_seconds() // 3600)
            if not "出席ボタン" in ev["name"]:    
                assignments.append({
                    "course": ev["course"]["fullname"],
                    "name": ev["name"],
                    "due": dt.strftime("%Y/%m/%d %H:%M"),
                    "remain": f"{remain_hours}時間後",
                    "url": ev.get("url", f"{MOODLE_URL}/my/")  # URLなければデフォルト
                })

        if return_data:
            return assignments
        else:
            for a in assignments:
                print(f"【{a['course']}】 {a['name']} (締切: {a['due']}) 残り: {a['remain']}")

    except MoodleClientError as e:
        print(f"[ERROR] {e}", file=sys.stderr)
        return [] if return_data else None
    except (requests.RequestException, KeyError, ValueError) as e:
        print(f"[ERROR] 通信または解析で例外発生: {e}", file=sys.stderr)
        return [] if return_data else None


def check_moodle_credentials(username: str, password: str) -> bool:
    """
    Moodleのユーザー名とパスワードが正しいかを検証する。
    APIリクエストが成功するかどうかで認証判定を行う。
    """
    try:
        sd = login_and_get_session(username, password)
        m = re.search(r'"sesskey":"([^"]+)"', sd.html)
        if not m:
            print("[CHECK] sesskey が見つかりません")
            return False
        sesskey = m.group(1)

        method_name = "core_calendar_get_action_events_by_timesort"
        ajax_url = f"{MOODLE_URL}/lib/ajax/service.php?sesskey={sesskey}&info={method_name}"

        now = datetime.now(timezone.utc)
        future = now + timedelta(days=1)

        ajax_payload = [{
            "index": 0,
            "methodname": method_name,
            "args": {
                "limitnum": 1,
                "timesortfrom": int(now.timestamp()),
                "timesortto": int(future.timestamp()),
                "limittononsuspendedevents": True,
            }
        }]

        headers = {
            "Content-Type": "application/json",
            "Referer": DASHBOARD_URL,
        }

        r = sd.session.post(ajax_url, data=json.dumps(ajax_payload), headers=headers, timeout=30)
        r.raise_for_status()
        j = r.json()

        if j[0].get("error"):
            print(f"[CHECK] Moodle API エラー: {j[0]['exception']['message']}")
            return False

        return True

    except MoodleClientError as e:
        print(f"[CHECK] MoodleClientError: {e}")
        return False
    except (requests.RequestException, KeyError, ValueError) as e:
        print(f"[CHECK] 通信/解析エラー: {e}")
        return False


if __name__ == "__main__":
    USERNAME = ""
    PASSWORD = ""
    get_assignments_true_ending(username=USERNAME, password=PASSWORD)
