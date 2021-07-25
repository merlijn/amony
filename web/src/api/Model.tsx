export class Video {
  constructor(
    public title: string,
    public thumbnail_uri: string,
    public previews: Array<Thumbnail>,
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
    public currentPage: number,
    public pageSize: number,
    public total: number,
    public videos: Video[]
  ) {
  }
}

export type Tag = {
  id: number,
  title: string
}

export type Thumbnail = {
  timestamp_start: number,
  timestamp_end: number,
  uri: string
}