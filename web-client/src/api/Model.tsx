export type Video = {
  id: string,
  video_url: string,
  meta: VideoMeta,
  thumbnail_url: string,
  preview_thumbnails_url: string,
  fragments: Array<Fragment>,
  fps: number,
  width: number,
  height: number,
  duration: number, // in millis
  addedOn: number
}

export type VideoMeta = {
  tags: string[]
  title: string
  comment?: string
}

export type SearchResult = {
  total: number
  videos: Video[]
}

export type Resolution = {
  value: number,
  label: string
}

export type Directory = {
  id: number,
  title: string
}

export type Range = {
  from: number,
  to: number
}

export type Fragment = {
  media_id: string,
  range: Range,
  index: number,
  urls: string[],
  tags: string[]
}

export type Sort = {
  field: string
  direction: SortDirection
}

export type SortDirection = 'asc' | 'desc'
export type Columns = 'auto' | number

export type Prefs = {
  showSidebar: boolean
  showTitles: boolean
  showDuration: boolean
  showMenu: boolean
  showDates: boolean
  gallery_columns: Columns
  sort: Sort
  videoQuality: number
}
