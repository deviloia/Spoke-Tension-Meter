# Spoke Tension Meter (Android / Java)

搭配輻條張力計使用的 App：輸入每根輻條的讀數 → 換算張力 (kgf) → 顯示整輪張力分佈，並存到 Firestore。

## 第 1 步：連上 Firestore

1. 到 [Firebase Console](https://console.firebase.google.com/) 建立專案。
2. **新增 Android 應用程式**，套件名稱填 `com.spoketension.meter`（要和 `app/build.gradle` 的 `applicationId` 一樣）。
3. 下載 `google-services.json`，放到 **`app/`** 資料夾底下（和 `app/build.gradle` 同一層）。
   這個檔案已經寫在 `.gitignore`，不會被 commit 進 repo。
4. 在 Console 左側選 **Firestore Database → 建立資料庫**，開發階段先選 **測試模式**（test mode）。
   測試模式的規則大約 30 天後會到期，到期後要改規則才能繼續讀寫。
5. 用 Android Studio 開啟這個資料夾，等 Gradle Sync 完成後，在手機或模擬器上執行。
6. 按 **「測試 Firestore 連線」**：
   - 成功：畫面會顯示「連線成功」和文件 ID，Console 的 `connection_test` collection 裡也會出現一筆新文件。
   - 失敗：常見原因：
     - `PERMISSION_DENIED`：Firestore 規則不允許讀寫，改用測試模式或修改規則。
     - `File google-services.json is missing`：檔案沒放在 `app/` 底下。
     - 一直卡在「連線中…」：模擬器或手機沒有網路，或還沒建立 Firestore 資料庫。

## 專案結構

```
app/src/main/java/com/spoketension/meter/
├── MainActivity.java              # 目前只有連線測試畫面
└── data/FirestoreRepository.java  # 所有 Firestore 存取都放這裡
```
