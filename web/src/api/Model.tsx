export class Video {
  constructor(
    public title: string,
    public thumbnail: string,
    public id: string,
    public resolution: string,
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