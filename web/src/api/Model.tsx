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
    public duration: number // in millis
  ) { }
}

export class SearchResult {

  constructor(
    public total: number,
    public videos: Video[]
  ) {
  }
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

export type Prefs = {
  showTitles: boolean
  showDuration: boolean
  showMenu: boolean
  gallery_columns: number;
}

export const defaultPrefs: Prefs = {
  showTitles: false,
  showDuration: true,
  showMenu: true,
  gallery_columns: 0
}