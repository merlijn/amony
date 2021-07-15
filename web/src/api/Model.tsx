export class Video {
  constructor(
    public title: string,
    public thumbnail: Thumbnail,
    public id: string,
    public resolution: string,
    public resolution_x: number,
    public resolution_y: number,
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

export type Collection = {
  id: number,
  title: string
}

export type Thumbnail = {
  timestamp: number,
  uri: string,
  webp_uri: string
}