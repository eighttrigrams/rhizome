(ns ui.refusal
  "The banner a refused write is reported in.

   Built for the acyclicity refusal in the edit modal, and shared from here now
   that the unlink refusal joins it. The two are the same class of message: a
   rule about the part-of layer declined the write, nothing was written, and the
   user can act on it. Showing them the same way is the point -- a refusal that
   arrives as its own invention reads as a different kind of event than it is.

   The class names are the ones the modal already carried, and the e2e scenarios
   locate the banner by them, so they stay as they are even though `.part-of-`
   now overreaches slightly: it is the banner's name, not the rule's.")

(defn component
  "`message` is the refusal itself, as the backend worded it -- the rule states
   its own sentence, so the UI does not paraphrase it. `hint` is what to do
   about it, which only the UI knows, since it depends on which gesture was
   refused."
  [message hint]
  [:div.part-of-refusal [:div message] [:div.part-of-refusal-hint hint]])
