# Aurioのロゴとアプリアイコン

提供されたリングとワードマークをローズの標準ロゴに使います。設定の「デザイン」で、ローズ、サファイア、ジェイド、アメジストからホーム画面のアイコンを選べます。

![Androidのランチャーに表示するローズ、サファイア、ジェイド、アメジスト](screenshots/v110/launcher-colors.png)

設定の見本にはワードマークを含む画像を使います。ホーム画面ではリングを中央に置き、丸型のマスクでも背景の余白が残るようにしています。4色とも `icon` と `roundIcon` に同じ色の適応型アイコンを指定しています。テーマ付きアイコンと通知には単色のリングを使います。 [Androidの適応型アイコン](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)

`LauncherIcon` はランチャー用の `activity-alias` を1つ有効にします。既定の入口には従来のコンポーネント名を使っています。アイコンから開く短時間の起動処理と、設定や再生を表示する画面には別々のタスクを割り当てています。メディアの通知は表示用の画面を直接開きます。ランチャーがアイコンを更新する時期は端末によって異なります。 [Androidのアクティビティ別名](https://developer.android.com/guide/topics/manifest/activity-alias-element) · [タスクとバックスタック](https://developer.android.com/guide/components/activities/tasks-and-back-stack)
