import React from 'react';
import {doGET} from '../api/Api';
import {Video} from '../api/Model';
import Thumbnail from './Thumbnail';
import Pagination from 'react-bootstrap/Pagination';
import './Gallery.scss';

class State {
  constructor(
    public movies: Video[]
  ) { }
}

class Props {
  constructor(
    public query?: string
  ) { }
}

class Gallery extends React.Component<Props, State> {

  constructor(props: Props) {

    super(props);

    let width: number = 459;
    let height: number = Math.ceil(width / 16 * 9);

    this.state = {movies: []};
  }

  componentDidMount = () => {
    this.doQuery();
  };

  componentDidUpdate = (prevProps: Props) => {

    if (prevProps.query != this.props.query)
      this.doQuery();
  };

  doQuery = () => {

    const path = (this.props.query) ? '/api/videos?q=' + this.props.query : '/api/videos';

    doGET(path)
      .then(videos => {
        this.setState({movies: videos});
      });
  }

  render = () => {

    const ncols: number = 3;
    const nrows: number = Math.ceil(this.state.movies.length / ncols);

    let rows = [...new Array(nrows)].map((e, y) => {
      let cols = [...new Array(ncols)].map((e, x) => {

        const idx = (y * ncols) + x;

        if (idx <= this.state.movies.length - 1) {
          const movie: Video = this.state.movies[idx];
          const m: string = movie.thumbnail;
          const link: string = "/files/videos/" + movie.id;
          return (
            <td className="gallery-column">
              <Thumbnail src={m} link={link} title={movie.title}/>
            </td>);
        } else {
          return <td className="gallery-column"></td>;
        }

      });
      return <tr className="gallery-row full-width"> {cols} </tr>;
    });

    return (
      <div className="full-width">
        <table className="gallery">
          {rows}
        </table>
        <Pagination>
          <Pagination.First/>
          <Pagination.Prev/>
          <Pagination.Item active>{1}</Pagination.Item>
          <Pagination.Item>{2}</Pagination.Item>
          <Pagination.Ellipsis/>
          <Pagination.Item>{10}</Pagination.Item>
          <Pagination.Next/>
          <Pagination.Last/>
        </Pagination>
      </div>
    );
  };
}

export default Gallery;
