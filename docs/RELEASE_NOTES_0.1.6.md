# Third Ear 0.1.6

- **Leaving a White Noise group works, and says what happened.** Before, a failed leave showed a bare hex code and the group stayed. If Third Ear is an admin, it now gives up admin rights before it leaves. If it is the group's only admin, which is the case for a test group made in Third Ear, you can remove the group from this phone instead. While a leave waits for the group to confirm, the group shows as leaving. Groups you have left stay in the list with a Remove button. A line that still sends to such a group shows a warning.
- **Pick the name your White Noise groups see.** Setup → White Noise → Name in groups. "Third Ear (Anna's phone)" says whose it is; plain "Third Ear" looked like a bot.
- **Sending a line to a group works on the first try.** When White Noise is off, the line editor turns it on in place, then offers to create a test group and selects it. If you close a new line without saving, the next Add line brings back what you typed. Until you join a group, the key-backup reminder is a plain note, not an orange alarm.
- **Webhooks need https://.** Android never let Third Ear send to an http:// address anyway, so such a webhook only failed later, when a recording was sent. The editor now says so up front. A webhook carries your recording and its transcript, so it should be encrypted.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`
