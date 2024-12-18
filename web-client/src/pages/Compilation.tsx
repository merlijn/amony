import { useState, useEffect } from "react";
import { Api } from "../api/Api";
import { Clip, SearchResult } from "../api/Model";
import FragmentsPlayer from "../components/common/FragmentsPlayer";

const Compilation = () => {

    const [fragments, setFragments] = useState<Array<Clip>>([])
  
    useEffect(() => {
  
        Api.getMedias("", 24, 0).then(response => {
            const f = response as SearchResult
            const frags = f.results.flatMap((v) => { return v.clips })
            setFragments(frags)
        });
      }, []
    )
  
    if (fragments.length > 0) {
      return <FragmentsPlayer 
                style = { { width: 800, height: 500 } }
                className = "abs-center"
                fragments = { fragments } 
              />
    } else
      return <div />
  }

export default Compilation