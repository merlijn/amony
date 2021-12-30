export type Video = {
  id: string,
  video_url: string,
  meta: VideoMeta,
  thumbnail_url: string,
  preview_thumbnails_url: string,
  fragments: Array<Fragment>,
  fps: number,
  size: number,
  width: number,
  height: number,
  duration: number, // in millis
  addedOn: number
}

export type Fragment = {
  media_id: string,
  range: Range,
  index: number,
  urls: string[],
  tags: string[]
}

export type VideoMeta = {
  tags: string[]
  title: string
  comment?: string
}

export type MediaSelection = {
  query?: string
  playlist?: string
  tag?: string
  duration?: [number?, number?]
  minimumQuality: number
  sort: Sort
}

export type MediaView = 'grid' | 'list'

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
}
