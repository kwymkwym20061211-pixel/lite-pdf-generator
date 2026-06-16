## 次回接続時の手順

```powershell
# Windows（管理者不要）
adb kill-server
usbipd attach --wsl --busid 1-3
```

```bash
# WSL
adb devices
# → デバイスが表示されれば完了
```