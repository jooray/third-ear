# Third Ear 0.1.1

- **White Noise invites work.** White Noise looks people up on its own relays, and Third Ear published its relay lists and chat key only to the public ones, so inviting it failed with "We couldn't find a usable chat key". Third Ear now also publishes to relay.eu.whitenoise.chat and relay.us.whitenoise.chat. An existing identity updates its relay lists and publishes a fresh chat key the first time it starts.
- If White Noise does not start, it tries again by itself a few times, and the error names what went wrong instead of showing nothing.

Signing certificate SHA-256: `79750fb49349ec3729be5a3e825507a15e47c6d987e854ae91d5d50a20b3a24d`
