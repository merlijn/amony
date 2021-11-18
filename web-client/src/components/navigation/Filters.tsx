import { Form } from 'react-bootstrap';
import { Constants } from '../../api/Constants';
import { Prefs } from '../../api/Model';
import { useCookiePrefs } from '../../api/ReactUtils';
import './Filters.scss';

const Filters = () => {

  const [prefs, setPrefs] = useCookiePrefs<Prefs>("prefs", "/", Constants.defaultPreferences)
  const updatePrefs = (values: {}) => { setPrefs({...prefs, ...values} ) }
  
  return(
    <div className="filter-bar">
        
        <div key="filter-form" className="config-form">

        <div className="form-section">
          <div className="form-label">Sort by</div>
          <div className="form-content">
            <select style={ { float: "left" } } className="mr-2" name="sort-field" onChange={(e) => { updatePrefs( { sortField: e.target.value }) } }>
              {
                Constants.sortOptions.map((v) => {
                  return <option
                    selected = { prefs.sortField === v.value }
                    key= { `sorting-${v.value}` }
                    value={v.value}
                    label={v.label}
                  />;
                })
              }
            </select>
            <Form.Check
              label="reverse"
              style={ { float: "left" } }
              type="checkbox"
              checked={ prefs.sortDirection === "desc" }
              onChange={(e) => {
                  updatePrefs( { sortDirection: (prefs.sortDirection === "asc") ? "desc" : "asc" })
                }
              }
            />
          </div>
        </div>

        <div className="form-section">
          <div className="form-label">Video quality</div>
          <div className="form-content">
            {
              Constants.resolutions.map((v) => {
                return <Form.Check
                  style={ { float:"left" } }
                  className="mr-1"
                  name="resolution"
                  type="radio"
                  key={`resolution-${v.value}`}
                  value={v.value}
                  label={v.label}
                  checked={prefs.minRes === v.value}
                  onChange={(e) => { updatePrefs( { minRes: v.value }) } }
                />;
              })
            }
          </div>
        </div>
      </div>
    </div>
  );
}

export default Filters