# Privacy policy: tuxwire for Android

Last updated: 2026-10-15

tuxwire is an RSS reader. It does not collect your data, and nothing you do in
it is sent to the developer. There are no accounts, no ads, no analytics, no
tracking and no crash reporting built into the app.

## What stays on your device

Your feeds, the articles downloaded from them, the articles you save, your
notes and your settings are stored in the app's private storage on your phone.
They are not copied to Google's cloud backups, and uninstalling the app removes
them. The app asks for one permission only: `INTERNET`.

## What the app sends over the network

The app contacts only these places:

1. **The feeds you add.** To refresh a feed, tuxwire requests that feed's
   address. When you type a blog's address instead of a feed address, it
   requests that site to find the feed. Each request identifies the app as
   `tuxwire/<version>`, and, as with any web request, the server can see your
   IP address. Those servers belong to other people and follow their own
   policies.
2. **Your own sync server, only if you turn sync on.** Sync is off by default
   and runs only when you tap **sync now**. When used, it sends your saved
   articles and notes to the CouchDB server whose address, database name,
   username and password you entered. The developer has no access to it. The
   address must use `https://` (plain `http://` is allowed only for the device
   itself). Your password is stored encrypted with the Android Keystore, never
   in plain text. **forget everything** in the sync screen (it asks first)
   wipes these settings.

Opening an article in your browser hands the link to your browser, and the
browser's own privacy policy applies from there. The Share menu entry receives
only the text you choose to share.

## Files you export

Markdown and OPML exports are written to the location you pick in Android's file
picker. If you pick a cloud folder, that service's policy applies to the file.

## Your control

You can un-save an article or delete a note at any time (the app asks before it
does either), remove feeds, turn sync off, and uninstall the app. Data already
copied to your own sync server is deleted on that server.

## Google Play

If you installed from Google Play, Google may collect crash and performance
data (Android vitals) under its own policy, depending on your device settings.
That collection is done by Google, not by tuxwire.

## Changes and questions

If this policy changes, the new version is published in this file, with its
history in the repository. Questions can be raised as an issue at
https://github.com/shanecuster/tuxwire-android/issues.
