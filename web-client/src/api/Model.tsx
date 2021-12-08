export class Video {
  constructor(
    public id: string,
    public video_url: string,
    public meta: VideoMeta,
    public thumbnail_url: string,
    public preview_thumbnails_url: string,
    public fragments: Array<Fragment>,
    public fps: number,
    public width: number,
    public height: number,
    public duration: number, // in millis
    public addedOn: number
  ) { }
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
