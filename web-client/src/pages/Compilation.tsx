import { useState, useEffect } from "react";
import { Api } from "../api/Api";
import { Fragment, SearchResult } from "../api/Model";
import FragmentsPlayer from "../components/shared/FragmentsPlayer";

const Compilation = () => {

    const [fragments, setFragments] = useState<Array<Fragment>>([])
  
    useEffect(() => {
  
        Api.getVideos("", 24, 0).then(response => {
            const f = response as SearchResult
            const frags = f.videos.flatMap((v) => { return v.fragments })
            setFragments(frags)
        });
      }, []
    )
  
    if (fragments.length > 0) {
      return <FragmentsPlayer 
                id = "all-fragments"
                style = { { width: 800, height: 500 } }
                className = "abs-center"
                fragments = { fragments } 
              />
    } else
      return <div />
  }

export default Compilation