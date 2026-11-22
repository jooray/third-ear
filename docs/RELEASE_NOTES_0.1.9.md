# Third Ear 0.1.9

- **Mention an agent with every message (optional).** A White Noise group's settings (Lines) have "Mention with every message": nobody, as before, or one member of the group. With a member chosen, everything Third Ear sends there (line deliveries, typed messages, notification replies) carries a mention of them, so an agent gateway that only answers when mentioned (wn-agent in a group with more people) acts on your voice notes. The text is not changed. (#13, #16)
- **Delete a sent message for everyone.** Your own messages in a conversation have Delete. Other members' apps show the message as deleted. A message that never left the phone gets Discard instead, which only removes it here. (#15)
- **Mentions read as names.** A mention someone writes in White Noise (@Third Ear, @someone) shows as the name in conversations and notifications, and is read aloud as the name, instead of a 63-character npub.
- **Webhooks over a VPN.** An address typed without https:// gets it added. A plain http:// address works again after you confirm it for that line, since the recording and transcript travel unencrypted. That's fine on Tailscale, Nostr VPN or WireGuard, which encrypt the traffic themselves.
- **White Noise on MarmotKit 0.18.0.** Group changes that reached no relay no longer leave a group stuck on "leaving", and a voice note sent just after the group changed is uploaded again instead of failing. Errors that used to show as an empty message are now a sentence. The White Noise data on the phone is upgraded once and can't be opened by 0.1.8 afterwards.
- Updated libraries (Kotlin 2.4.21, coroutines 1.11.0).

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`
