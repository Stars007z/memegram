# User Stories — Memegram

---

## User

**- As a user, I should be able to register (important)**  
(Button “Register” → enter phone number → confirm via SMS → enable 2FA (optional) → create account (enter nickname, choose avatar, create username) → transition to authorized app state)

---

**- As a user, I should be able to log in (important)**  
(Button “Log In” → enter phone number → enter SMS code / if 2FA enabled – enter one-time code → transition to authorized state)

---

**- As a user, I should be able to edit my personal information (important)**  
(Authorized state → Settings → Profile → Edit name, photo, status, or 2FA → Save changes)

---

**- As a user, I should be able to log out (important)**  
(Authorized state → Settings → “Log Out” → confirmation → transition to unauthorized state)

---

**- As a user, I should be able to delete my account (important)**  
(Authorized state → Settings → Privacy → “Delete Account” → confirmation → transition to unauthorized state)

---

**- As a user, I should be able to start a private chat (important)**  
(Authorized state → “New Chat” button → search user by nickname/phone/username → select user → open chat window)

---

**- As a user, I should be able to create a group chat (important)**  
(Authorized state → “Create Group” button → select participants from contact list → enter name and group photo → confirm creation)

---

**- As a user, I should be able to send and receive messages (important)**  
(Open chat → enter text → “Send” button → message appears in list → receive messages from other users in real time)

---

**- As a user, I should be able to send images, videos, and voice messages (important)**  
(Open chat → “Attachment” or “Microphone” button → select media file or record → send → display in chat with preview)

---

**- As a user, I should be able to create and use folders to organize chats (secondary)**  
(Authorized state → Settings → “Folders” section → “Create Folder” button → enter name → add chats → save → display in interface)

---

**- As a user, I should be able to enable or disable NSFW content filtering (important)**  
(Authorized state → Settings → Privacy → “Filter NSFW” toggle → choose state → save)

---

**- As a user, I should be able to change the interface appearance (secondary)**  
(Authorized state → Settings → Appearance → choose theme, color, font → save changes)

---

**- As a user, I should be able to receive notifications about new messages and events (important)**  
(Authorized state → receive push notifications about new messages, mentions, or chat invitations → manage in “Settings”)

---

**- As a user, I should be able to view media files in chat (secondary)**  
(Authorized state → open chat → “Media” tab → view images, videos, and voice messages)

---

**- As a user, I should be able to search chats and messages (secondary)**  
(Authorized state → top bar → search field → enter text → display chats and messages matching the query)

---

**- As a user, I should be able to subscribe to channels anonymously (secondary)**  
(Authorized state → “Channels” tab → select channel → “Subscribe” button → user receives updates without revealing name)

---

**- As a user, I should be able to report a user or content (secondary)**  
(Authorized state → chat or message → “Report” button → choose reason → send report to moderation)

---

**- As a user, I should be able to pin a chat (secondary)**  
(Authorized state → long tap on chat → “Pin” button → chat appears at the top of the list)

---

**- As a user, I should be able to archive a chat (secondary)**  
(Authorized state → long tap on chat → “Archive” button → chat moves to archive → accessible via “Archive” tab)

---

**- As a user, I should be able to edit and delete my messages (secondary)**  
(Open chat → hold message → “Edit” or “Delete” button → modify or remove → chat updates)

---

**- As a user, I should be able to enable two-factor authentication (2FA) (important)**  
(Authorized state → Security Settings → enable 2FA → confirm via SMS → enter backup code → activate protection)

---

## Administrator

**- As an administrator, I should be able to block or unblock users (important)**  
(Admin panel → “Users” tab → search for a user → “Block/Unblock” button → change status)

---

**- As an administrator, I should be able to view user reports (secondary)**  
(Admin panel → “Reports” tab → list of reports → view details → choose action: “Delete content” or “Ignore”)

---

**- As an administrator, I should be able to manage channel content (secondary)**  
(Admin panel → “Channels” tab → view list → remove NSFW or violating content if necessary)

---
