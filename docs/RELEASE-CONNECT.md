# Aurioのアプリ内更新と配布手順

Aurioはビルド設定に固定した公開リポジトリの安定版を確認します。更新対象はGitHubで `latest` に指定したリリースです。古い版や同じ版は更新候補に入りません。 [公式リポジトリ](https://github.com/noppy3325-droid/Aurio/releases) · [GitHubのリリースAPI](https://docs.github.com/en/rest/releases/releases#get-the-latest-release)

## 配布するファイル

同じリリースに次の2ファイルを載せます。

- `Aurio-<versionName>.apk`。単体でインストールできるリリース用APKです。アプリIDは `jp.noppy.lumamusic` で、公開版と同じ署名を使います。
- `update.json`。APKの `versionName`、`versionCode`、`minSdk`、`abis`、`assetName`、`size`、`sha256` が入ります。

タグは `v<versionName>` とし、`versionCode` は過去の配布版より大きくします。APKのサイズとSHA-256は署名後のファイルから求めます。ネイティブライブラリを含むAPKでは実際のABIを列挙します。含まない場合は `abis: ["universal"]` を使います。

## 公開前の確認

従来の鍵で署名したAPKを用意します。PowerShell 7とJDK、Android SDKのBuild Tools 36.0.0を使い、次の順に実行します。

```powershell
./scripts/Test-ReleaseApk.ps1 -ApkPath release/Aurio-1.5.0.apk -Tag v1.5.0 -ManifestPath release/update.json -Generate
./scripts/Test-ReleaseApk.ps1 -ApkPath release/Aurio-1.5.0.apk -Tag v1.5.0 -ManifestPath release/update.json
```

GitHub Actionsでは、同一リポジトリの成功した実行で作った2ファイル入りのアーティファクトを、手動の `Release preflight` で検証できます。実行IDとアーティファクト名を指定します。このワークフローは読み取り権限で実行します。

## Aurioでの更新

自動確認をオンにすると、起動・復帰時に最終確認から6時間以上経った場合と、端末が通信可能なときの定期処理で更新を確認します。通知を表示するにはAurioの設定と端末の通知許可が必要です。ダウンロードはユーザーが開始し、中断した場合は最初からやり直せます。

検証済みAPKを標準インストーラーに渡す前に、再生キューと再生位置を保存します。Androidから求められた場合は、Aurioをインストール元として許可し、インストーラーで上書きを承認します。更新後は実際にインストールされたバージョンを確認します。 [Androidのインストーラー](https://developer.android.com/reference/android/content/pm/PackageInstaller) · [WorkManagerの定期処理](https://developer.android.com/develop/background-work/background-tasks/persistent/getting-started/define-work)
