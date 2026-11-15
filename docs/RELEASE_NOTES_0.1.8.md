# Third Ear 0.1.8

- **White Noise on MarmotKit 0.17.0.** Groups made by current White Noise apps can invite Third Ear again: on first start it publishes a new key package with the account-devices component those groups require. The White Noise data on the phone is upgraded once and can't be opened by 0.1.7 afterwards.
- **Fewer stuck groups.** An invite that would make Third Ear an admin (a White Noise direct chat does) can now be declined. A leave that can never finish, because everyone else left and Third Ear became the last admin, is cancelled instead of showing "leaving" forever; Leave then offers removing the group from this phone.
- **Test group from the line editor.** The White Noise group picker offers "Create a test group" with only Third Ear in it, to try a line without bothering anyone.
- **No more Fossify donation popup.** It came from the Fossify code Third Ear started from and asked for money for a different project. In its place: after five days of use and ten recordings, a small strip under the recordings in the Log asks once whether you'd like to support Third Ear (you get an e-book). "Not now" hides it, "Don't ask again" ends it for good, and it never shows while you record. It appears at most three times. Setup > About has "Support the project" if you want it later. The app sends nothing anywhere for this: the link opens the payment page in your browser.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`
