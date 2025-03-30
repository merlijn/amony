import React, { ChangeEvent, useState } from 'react';
import { dateMillisToString } from '../../api/Util';
import Dialog from '../common/Dialog';
 
const FileUpload = () => {
  
    const [file, setFile] = useState<File | undefined>(undefined)
    const [feedback, setFeedback] = useState<string | undefined>(undefined)

    // On file select (from the pop up)
    const onFileChange = (event: ChangeEvent<HTMLInputElement>) => {
    
      if (event.target.files) {
        setFile(event.target.files[0]);
      }    
    };
    
    // On file upload (click the upload button)
    const onFileUpload = () => {
    
      if (file) {
        // Api.uploadFile(file).then(() => {
        //   setFeedback("Done!")
        //   setFile(undefined)
        // });
      }
    };
    
    return (
      <Dialog title = "Upload media">
        <>
          <div>
              <input type="file" accept=".mp4" onChange = { onFileChange} />
              <button className="abs-bottom-right button-primary" onClick = { onFileUpload} >Upload</button>
          </div>
          { feedback && <div><p>{ feedback }</p></div>}
          {
            file && 
              <div>
                <p>File Type: {file.type}</p>
                <p>Last Modified: { dateMillisToString(file.lastModified) }</p>
              </div>
          }
        </>
      </Dialog>
    );
  }
 
export default FileUpload;