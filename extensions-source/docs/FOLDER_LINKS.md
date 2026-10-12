# DeviantArt Folder Links Resolution (`FOLDER_LINKS.md`)

This document outlines the link-resolution behavior and verified folder mapping implemented in accordance with FR-15.

---

## 1. Verified Folder Link Patterns

DeviantArt supports gallery folders identified by a numeric ID in the URL.

| Link Pattern | Example | Resolved Target |
|--------------|---------|-----------------|
| Full URL with slug | `https://www.deviantart.com/artist/gallery/12345678/my-folder` | `GalleryRef(artist, "12345678")` |
| Full URL without slug | `https://www.deviantart.com/artist/gallery/12345678` | `GalleryRef(artist, "12345678")` |
| URL without scheme | `deviantart.com/artist/gallery/12345678/my-folder` | `GalleryRef(artist, "12345678")` |
| Mixed-case URL with params | `www.deviantart.com/Artist/Gallery/12345678/My-Folder?page=2#top` | `GalleryRef(Artist, "12345678")` |
| Mobile subdomain | `https://m.deviantart.com/artist/gallery/12345678/my-folder` | `GalleryRef(artist, "12345678")` |
| User subdomain | `https://artist.deviantart.com/gallery/12345678/my-folder` | `GalleryRef(artist, "12345678")` |
| Link inside pasted text | `Look at this https://www.deviantart.com/artist/gallery/12345678/my-folder!` | `GalleryRef(artist, "12345678")` |

---

## 2. API Request Construction

For folder requests:
- Backend RSS: `https://backend.deviantart.com/rss.xml?type=deviation&q=gallery:{user}/{folderId}&offset={offset}`
- Web gallery: `https://www.deviantart.com/{user}/gallery/{folderId}`

For All gallery requests:
- Backend RSS: `https://backend.deviantart.com/rss.xml?type=deviation&q=gallery:{user}&offset={offset}`
- Web gallery: `https://www.deviantart.com/{user}/gallery/all`

---

## 3. Storage Separation

To guarantee folder entries and the All gallery entry for the same artist do not collide:
- Crawl Cache File: `filesDir/deviantart/{userLowercase}_{folderId|all}.json`
- Chapter Ledger File: `filesDir/deviantart/ledger_{userLowercase}_{folderId|all}.json`
