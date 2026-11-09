# VoiceBubble

A Wispr Flow-style dictation bubble for Android, with no weekly word limit.

Open any keyboard and a mic bubble appears just above it. Tap, talk, tap again. Your speech is turned into text **on the phone** (fast, private, works offline), tidied up by **Claude** (fillers gone, punctuation fixed, "scratch that" corrections applied, British spelling), then typed into the box you're in. Close the keyboard and the bubble disappears.

```
 ┌──────────── your phone ────────────┐        ┌──── Cloudflare ────┐      ┌─ Anthropic ─┐
 │ keyboard opens → bubble appears    │        │                    │      │             │
 │ tap → on-device speech recogniser  │  text  │  Worker adds your  │      │  Claude     │
 │ (words stream in as you talk)      │ ─────► │  Claude API key    │ ───► │  Haiku 5.5  │
 │ tap → send transcript              │ ◄───── │  (never on phone)  │ ◄─── │             │
 │ typed into the app you're using    │ tidied │                    │      │             │
 └────────────────────────────────────┘        └────────────────────┘      └─────────────┘
```

If the Worker or Claude is slow or down, the raw transcript is typed instead, so you never lose what you said.

| Folder | What it is |
| --- | --- |
| `android/` | Kotlin app: setup screen + accessibility service that shows the bubble and types the text |
| `worker/` | Cloudflare Worker that holds the Claude key and does the clean-up (with tests) |
| `.github/workflows/` | Deploys the Worker and builds the APK |

---

## 1. Add repository secrets

GitHub → this repo → **Settings → Secrets and variables → Actions → New repository secret**.

| Secret | Where to get it |
| --- | --- |
| `ANTHROPIC_API_KEY` | Your Claude API key. If yours is saved as `CLAUDE_API_KEY`, that works too. |
| `CLOUDFLARE_API_TOKEN` | Cloudflare dashboard → My Profile → API Tokens → Create Token → **Edit Cloudflare Workers** template. |
| `CLOUDFLARE_ACCOUNT_ID` | Cloudflare dashboard → Workers & Pages. It's shown on the right (or in the URL). |
| `APP_TOKEN` | A random password the app uses to talk to your Worker. Make one below and **keep a copy**: you'll paste it into the app once. |

Make `APP_TOKEN` in PowerShell:

```powershell
$b = New-Object byte[] 32; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
-join ($b | ForEach-Object { $_.ToString("x2") }) | Set-Clipboard   # now on your clipboard
```

## 2. Signing key (already done)

Every build is signed with the app's own key, `android/signing/voicebubble.p12`, so new versions install straight over the old one. Its password is your `APP_TOKEN` secret, so there's nothing to set up.

> If you ever change `APP_TOKEN`, also add a `SIGNING_STORE_PASSWORD` secret holding the **old** token, or builds will fail to sign.

**Optional: use your own key instead.** Changing key means uninstalling the app once. On your PC (keytool comes with Android Studio, in `Android Studio\jbr\bin`, or any JDK):

```powershell
keytool -genkeypair -v -keystore voicebubble.jks -alias voicebubble -keyalg RSA -keysize 4096 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes("voicebubble.jks")) | Set-Clipboard
```

Then add four more secrets:

| Secret | Value |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | What's on your clipboard from the second command |
| `SIGNING_STORE_PASSWORD` | The keystore password you chose |
| `SIGNING_KEY_ALIAS` | `voicebubble` |
| `SIGNING_KEY_PASSWORD` | The key password (same as the store password unless you set a different one) |

Back up `voicebubble.jks` and its password somewhere safe (a password manager). Don't commit it; `.gitignore` already blocks `*.jks`. When these secrets exist they take priority over the app's own key.

## 3. Deploy the Worker

**Actions → Clean-up Worker → Run workflow.** When it finishes, open the run: the summary shows your **Worker URL** (`https://voicebubble-cleanup.<you>.workers.dev`). Copy it.

It redeploys automatically whenever you change anything in `worker/`.

## 4. Get the APK

Every change to `android/` builds automatically (or run **Actions → Android app → Run workflow**). A few minutes later, the newest version is always at:

**https://github.com/Khalifa996/voicebubble/releases/latest/download/VoiceBubble.apk**

Open that in Chrome on your phone (signed in to GitHub), then open the downloaded file to install or update. Bookmark it.

Want a named version as well? **Releases → Draft a new release → tag `v0.2.0` → Publish** attaches a copy to that release too.

## 5. Install and set up on the Fold

1. Download the APK on the phone and open it. Allow your browser / My Files to install apps if asked. If Samsung blocks it, turn off **Settings → Security and privacy → Auto Blocker** for the install (you can turn it back on after).
2. Open **VoiceBubble** and work down the Setup checklist:
   - **Microphone** → Allow.
   - **Accessibility** → Open → Installed apps → VoiceBubble → On.
     If it's greyed out with "Restricted setting": tap **App info** in VoiceBubble, then **⋮ → Allow restricted settings**, and try again. (Android does this for any app installed outside the Play Store.)
   - **Battery** → set to Unrestricted, so One UI doesn't put the bubble to sleep.
   - **Offline speech model** → Download if offered. This is what makes recognition instant and offline.
3. Under **AI clean-up**, paste the **Worker URL** and **APP_TOKEN**, then tap **Test connection**. You should see the sample sentence come back cleaned up.
4. Add names you use often under **Your words** (people, places, products). They help both the recogniser and Claude spell them right.
5. Tap the **Try it** box and use the bubble.

## Using it

- **Tap** the bubble → it turns red and sound bars next to it move with your voice. **Tap again** to finish, or tap **✕** to throw it away (nothing is typed).
- It keeps listening through pauses and auto-stops after 30 seconds of silence or 5 minutes.
- **Drag** the bubble to move it; **Reset bubble position** in the app puts it back.
- Say "scratch that" or "no wait, I mean…" to correct yourself. Say "new paragraph", "full stop", "bullet point" for layout.
- Claude matches the app: casual in WhatsApp/Messages, paragraphs in Gmail/Outlook, bullets in Notes/Keep.
- The bubble never appears on password fields.
- The text is typed by pasting, so it also stays on your clipboard. If an app won't accept it, you'll see a toast: just long-press and paste.

## Cost

- **Speech recognition:** free, on the phone.
- **Cloudflare Worker:** free plan is plenty for personal use.
- **Claude Haiku 5.5:** $0.10 per million input tokens and $0.50 per million output. A typical dictation (prompt plus a couple of sentences) is under a thousand tokens, so a dollar covers thousands of dictations.

To try a different Claude model, change `MODEL` in `worker/wrangler.toml` and push.

## Privacy

- Audio never leaves the phone when the offline model is installed. Only the finished transcript goes to your own Worker, then to Claude.
- Your Claude key lives only in GitHub secrets and Cloudflare. The APK contains no secrets, so it's safe to share.
- The accessibility service only listens for window and focus changes (to know when a keyboard is open and where to type). It doesn't read screen content. See `android/app/src/main/res/xml/accessibility_service_config.xml`.

## Troubleshooting

| Problem | Fix |
| --- | --- |
| No bubble | Accessibility service on? Battery unrestricted? Toggle the service off and on. Some keyboards in "floating" mode report odd positions: drag the bubble. |
| "AI clean-up skipped (wrong app token)" | Re-paste `APP_TOKEN` in the app, or re-run the Worker workflow after changing the secret. |
| "AI clean-up skipped (worker not configured)" | Re-run **Clean-up Worker**; check the four secrets in step 1 exist. |
| "No connection for speech recognition" | Download the offline speech model (Setup checklist), or check you're online. |
| Recognition sounds slow | Make sure "Prefer on-device speech recognition" is on and the model shows as ready. |
| Text doesn't appear in one app | It's on the clipboard: long-press the box and paste. Some apps block automated typing. |
| Update won't install ("conflicts with an existing package") | The installed copy was signed with a different key (builds before v0.1.7 used a throwaway key). Uninstall once, reinstall, and redo the setup. |

## Developing

```bash
cd worker && node --test          # Worker tests
cd android && ./gradlew assembleDebug
```

Open `android/` in Android Studio to run on the phone over USB or wireless debugging.
