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

export type Fragment = {
  media_id: string,
  timestamp_start: number,
  timestamp_end: number,
  index: number,
  urls: string[],
  tags: string[]
}

export type SortDirection = 'asc' | 'desc'
export type Columns = 'auto' | number

export type Prefs = {
  showTitles: boolean
  showDuration: boolean
  showMenu: boolean
  gallery_columns: Columns
  sortField: string,
  sortDirection: SortDirection,
  minRes: number
}
