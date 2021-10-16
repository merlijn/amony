export class Video {
  constructor(
    public id: string,
    public meta: VideoMeta,
    public thumbnail_uri: string,
    public preview_thumbnails_uri: string,
    public fragments: Array<Fragment>,
    public fps: number,
    public resolution_x: number,
    public resolution_y: number,
    public duration: number, // in millis
    public addedOn: number
  ) { }
}

export type VideoMeta = {
  tags: string[]
  title: string
  comment?: string
}

export class SearchResult {

  constructor(
    public total: number,
    public videos: Video[]
  ) {
  }
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
  timestamp_start: number,
  timestamp_end: number,
  index: number,
  uri: string,
  tags: string[]
}

export type SortDirection = "asc" | "desc"

export type Prefs = {
  showTitles: boolean
  showDuration: boolean
  showMenu: boolean
  gallery_columns: number
  sortField: string,
  sortDirection: SortDirection,
  minRes: number
}