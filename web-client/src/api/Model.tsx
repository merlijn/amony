export class Video {
  constructor(
    public title: string,
    public thumbnail_uri: string,
    public fragments: Array<Fragment>,
    public id: string,
    public fps: number,
    public resolution_x: number,
    public resolution_y: number,
    public tags: Array<string>,
    public duration: number, // in millis
    public addedOn: number
  ) { }
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

export type Tag = {
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