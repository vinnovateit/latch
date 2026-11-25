# Latch

Latch manages access to campus captive-portal Wi-Fi networks: it recognises a network, logs in, and keeps the resulting session healthy.

## Language

**KvIT**:
The user's hostel Wi-Fi network. Its name contains the campus fragment "vit" and its portal is expected to be a Pronto portal.
_Avoid_: KvT, hostel Wi-Fi

**Known Latch network**:
A Wi-Fi network Latch recognises as one it may log in to, based on its name and its portal.
_Avoid_: supported network, campus network

**Pronto portal**:
The captive-portal service used by known Latch networks; recognising it is what makes a recognised network latchable.
_Avoid_: captive portal, login page

**Limited connection**:
The operating system reporting no internet while Wi-Fi is still associated, shown to the user as a warning icon.
_Avoid_: disconnected, offline

**Latched**:
Having an active, healthy portal session on a known Latch network.
_Avoid_: connected, online

**Unlatch**:
Ending the local latched state and its live session, without ending the remote portal account session.
_Avoid_: log out, disconnect

**Logout**:
Ending the remote portal account session.
_Avoid_: unlatch

**Re-latch**:
Logging in again to the same known Latch network after its portal session becomes unhealthy.
_Avoid_: reconnect

**Wi-Fi reconnect**:
Reassociating the operating system's Wi-Fi client with the same network, used on desktop to repair a weak or broken link.
_Avoid_: re-latch, log in
