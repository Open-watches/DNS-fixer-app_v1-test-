import os
import glob
import requests

def upload_build():
    # 1. Fetch encrypted credentials from organization action vault safely
    bot_token = os.environ.get("BOT_TOKEN")
    targets = [os.environ.get("OWNER_ID"), os.environ.get("GROUP_ID")]
    
    if not bot_token:
        print("❌ Script Failure: Missing core TELEGRAM_BOT_TOKEN environment key.")
        return

    # 2. Automatically trace and isolate the compiled binary artifact
    apk_matches = glob.glob("app/build/outputs/apk/**/*.apk", recursive=True)
    if not apk_matches:
        print("❌ Script Failure: No compiled .apk binary located in the build tree.")
        return
        
    apk_path = apk_matches[0]
    print(f"📦 Production artifact discovered: {apk_path}")

    # 3. Process secure delivery loops across authorized targets
    url = f"https://api.telegram.org/bot{bot_token}/sendDocument"
    caption_text = "🚀 Cloud Compilation Complete! Secure build layer dispatched successfully."

    for chat_id in targets:
        if not chat_id:
            continue # Skip parameters left blank or unconfigured
            
        print(f"📡 Initializing handoff payload to terminal ID: {chat_id}...")
        
        try:
            with open(apk_path, "rb") as file_payload:
                response = requests.post(
                    url,
                    data={"chat_id": chat_id, "caption": caption_text},
                    files={"document": file_payload},
                    timeout=60  # Prevents script hanging indefinitely
                )
                
            if response.status_code == 200:
                print(f"✅ Secure handoff verified for terminal ID: {chat_id}")
            else:
                print(f"⚠️ API Rejection ({response.status_code}): {response.text}")
                
        except Exception as error_log:
            print(f"❌ Critical transmission collapse for destination {chat_id}: {error_log}")

if __name__ == "__main__":
    upload_build()
    