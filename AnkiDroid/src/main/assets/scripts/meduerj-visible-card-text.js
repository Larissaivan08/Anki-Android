// Read only the rendered card. Never inspect note fields, templates or stored answer HTML.
(() => {
    const card = document.getElementById("qa") || document.getElementById("content");
    if (!card) return "";
    const excluded =
        "script,style,noscript,button,input,textarea,select,audio,video,svg,[hidden],[aria-hidden='true']";
    const walker = document.createTreeWalker(card, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    const parts = [];
    let previousBlock = null;
    let node;
    while ((node = walker.nextNode())) {
        const element = node.nodeType === Node.ELEMENT_NODE ? node : node.parentElement;
        if (!element || element.closest(excluded)) continue;
        const isBreak =
            node.nodeType === Node.ELEMENT_NODE && (node.tagName === "BR" || node.tagName === "HR");
        let visible = true;
        let block = null;
        for (let parent = element; parent; parent = parent.parentElement) {
            const style = getComputedStyle(parent);
            if (!block && style.display !== "inline" && style.display !== "contents")
                block = parent;
            if (
                style.display === "none" ||
                style.visibility !== "visible" ||
                Number(style.opacity) === 0 ||
                ((parent !== element || !isBreak) &&
                    style.overflow === "hidden" &&
                    (parent.clientHeight === 0 || parent.clientWidth === 0))
            ) {
                visible = false;
                break;
            }
        }
        if (!visible || !element.getClientRects().length) continue;
        if (node.nodeType === Node.ELEMENT_NODE) {
            if (node.tagName === "BR" || node.tagName === "HR") parts.push(" ");
        } else {
            if (block !== previousBlock) parts.push(" ");
            parts.push(node.textContent);
            previousBlock = block;
        }
    }
    return parts.join("").replace(/\s+/g, " ").trim();
})();
