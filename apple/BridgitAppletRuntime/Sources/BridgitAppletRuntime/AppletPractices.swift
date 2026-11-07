import Foundation
import JavaScriptCore

/// Uses the shipped, generated evaluator. Candidate text is data, never executable code.
public enum AppletPractices {
    private static func evaluate(_ candidate: JSONValue, previous: JSONValue?, requireReview: Bool, metadataOnly: Bool) throws -> JSONValue {
        guard let context = JSContext() else { throw AppletRuntimeError.invalid("Practice evaluator unavailable") }
        var failure: String?
        context.exceptionHandler = { _, value in failure = value?.toString() ?? "Invalid practice metadata" }
        context.evaluateScript(AppletPracticeSource.engine)
        let encoder = JSONEncoder()
        context.setObject(try JSONSerialization.jsonObject(with: encoder.encode(candidate)), forKeyedSubscript:"candidate" as NSString)
        context.setObject(try previous.map { try JSONSerialization.jsonObject(with:encoder.encode($0)) } ?? NSNull(), forKeyedSubscript:"previous" as NSString)
        let expression = metadataOnly ? "checkPracticeMetadata(candidate,catalog,previous); JSON.stringify({})" : "JSON.stringify(\(requireReview ? "requirePracticeChange" : "assessPractices")(candidate,catalog,previous))"
        let result = context.evaluateScript(expression)?.toString()
        if let failure { throw AppletRuntimeError.invalid(failure) }
        guard let result, let data=result.data(using:.utf8) else { throw AppletRuntimeError.invalid("Invalid practice assessment") }
        return try JSONDecoder().decode(JSONValue.self,from:data)
    }
    public static func check(_ definition: JSONValue) throws { _ = try evaluate(definition,previous:nil,requireReview:false,metadataOnly:true) }
    public static func report(_ definition: JSONValue, previous: JSONValue? = nil) throws -> JSONValue { try evaluate(definition,previous:previous,requireReview:false,metadataOnly:false) }
    public static func requireChange(_ definition: JSONValue, previous: JSONValue? = nil) throws { _ = try evaluate(definition,previous:previous,requireReview:true,metadataOnly:false) }
}
